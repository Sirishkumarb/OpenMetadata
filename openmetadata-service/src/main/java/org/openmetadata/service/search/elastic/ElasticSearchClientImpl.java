package org.openmetadata.service.search.elastic;

import static javax.ws.rs.core.Response.Status.OK;
import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.service.Entity.FIELD_DESCRIPTION;
import static org.openmetadata.service.Entity.FIELD_DISPLAY_NAME;
import static org.openmetadata.service.Entity.FIELD_FOLLOWERS;
import static org.openmetadata.service.Entity.FIELD_NAME;
import static org.openmetadata.service.Entity.FIELD_USAGE_SUMMARY;
import static org.openmetadata.service.Entity.QUERY;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.PutMappingRequest;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.index.query.WildcardQueryBuilder;
import org.elasticsearch.index.query.functionscore.FieldValueFactorFunctionBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.MaxAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.SumAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.SuggestBuilders;
import org.elasticsearch.search.suggest.completion.CompletionSuggestionBuilder;
import org.elasticsearch.search.suggest.completion.context.CategoryQueryContext;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;
import org.openmetadata.schema.DataInsightInterface;
import org.openmetadata.schema.dataInsight.DataInsightChartResult;
import org.openmetadata.schema.entity.classification.Classification;
import org.openmetadata.schema.entity.classification.Tag;
import org.openmetadata.schema.entity.data.Database;
import org.openmetadata.schema.entity.data.DatabaseSchema;
import org.openmetadata.schema.entity.data.Glossary;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.entity.services.DashboardService;
import org.openmetadata.schema.entity.services.DatabaseService;
import org.openmetadata.schema.entity.services.MessagingService;
import org.openmetadata.schema.entity.services.MlModelService;
import org.openmetadata.schema.entity.services.PipelineService;
import org.openmetadata.schema.entity.services.StorageService;
import org.openmetadata.schema.entity.teams.Team;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.service.configuration.elasticsearch.ElasticSearchConfiguration;
import org.openmetadata.schema.type.ChangeDescription;
import org.openmetadata.schema.type.ChangeEvent;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.EventType;
import org.openmetadata.schema.type.FieldChange;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.type.UsageDetails;
import org.openmetadata.service.dataInsight.DataInsightAggregatorInterface;
import org.openmetadata.service.elasticsearch.ElasticSearchIndex;
import org.openmetadata.service.elasticsearch.ElasticSearchIndexDefinition;
import org.openmetadata.service.elasticsearch.ElasticSearchIndexFactory;
import org.openmetadata.service.elasticsearch.GlossaryTermIndex;
import org.openmetadata.service.elasticsearch.TagIndex;
import org.openmetadata.service.elasticsearch.TeamIndex;
import org.openmetadata.service.elasticsearch.UserIndex;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.jdbi3.DataInsightChartRepository;
import org.openmetadata.service.search.EntityBuilderConstant;
import org.openmetadata.service.search.IndexUtil;
import org.openmetadata.service.search.SearchClient;
import org.openmetadata.service.search.UpdateSearchEventsConstant;
import org.openmetadata.service.util.ElasticSearchClientUtils;
import org.openmetadata.service.util.JsonUtils;

@Slf4j
public class ElasticSearchClientImpl
    implements SearchClient<RestHighLevelClient, BulkResponse, BulkRequest, RequestOptions> {

  private final RestHighLevelClient client;

  private final CollectionDAO dao;

  private final IndexUtil indexUtil;

  public final String SEARCH_TYPE = "ElasticSearch";

  public ElasticSearchClientImpl(ElasticSearchConfiguration esConfig, CollectionDAO dao) {
    this.client = ElasticSearchClientUtils.createElasticSearchClient(esConfig);
    this.dao = dao;
    this.indexUtil = new IndexUtil(dao);
  }

  private static NamedXContentRegistry xContentRegistry;

  static {
    SearchModule searchModule = new SearchModule(Settings.EMPTY, false, List.of());
    xContentRegistry = new NamedXContentRegistry(searchModule.getNamedXContents());
  }

  /**
   * @param elasticSearchIndexType
   * @param lang
   * @return
   */
  @Override
  public boolean createIndex(ElasticSearchIndexDefinition.ElasticSearchIndexType elasticSearchIndexType, String lang) {
    try {
      GetIndexRequest gRequest = new GetIndexRequest(elasticSearchIndexType.indexName);
      gRequest.local(false);
      boolean exists = client.indices().exists(gRequest, RequestOptions.DEFAULT);
      if (!exists) {
        String elasticSearchIndexMapping = indexUtil.getIndexMapping(elasticSearchIndexType, lang);
        CreateIndexRequest request = new CreateIndexRequest(elasticSearchIndexType.indexName);
        request.source(elasticSearchIndexMapping, XContentType.JSON);
        CreateIndexResponse createIndexResponse = client.indices().create(request, RequestOptions.DEFAULT);
        LOG.info("{} Created {}", elasticSearchIndexType.indexName, createIndexResponse.isAcknowledged());
      }
      indexUtil.setIndexStatus(elasticSearchIndexType, IndexUtil.ElasticSearchIndexStatus.CREATED);
    } catch (Exception e) {
      indexUtil.setIndexStatus(elasticSearchIndexType, IndexUtil.ElasticSearchIndexStatus.CREATED);
      indexUtil.updateElasticSearchFailureStatus(
          indexUtil.getContext("Creating Index", elasticSearchIndexType.indexName),
          String.format(IndexUtil.REASON_TRACE, e.getMessage(), ExceptionUtils.getStackTrace(e)));
      LOG.error("Failed to create Elastic Search indexes due to", e);
      return false;
    }
    return true;
  }

  /**
   * @param elasticSearchIndexType
   * @param lang @Override
   */
  public void updateIndex(ElasticSearchIndexDefinition.ElasticSearchIndexType elasticSearchIndexType, String lang) {
    try {
      GetIndexRequest gRequest = new GetIndexRequest(elasticSearchIndexType.indexName);
      gRequest.local(false);
      boolean exists = client.indices().exists(gRequest, RequestOptions.DEFAULT);
      String elasticSearchIndexMapping = IndexUtil.getIndexMapping(elasticSearchIndexType, lang);
      if (exists) {
        PutMappingRequest request = new PutMappingRequest(elasticSearchIndexType.indexName);
        request.source(elasticSearchIndexMapping, XContentType.JSON);
        AcknowledgedResponse putMappingResponse = client.indices().putMapping(request, RequestOptions.DEFAULT);
        LOG.info("{} Updated {}", elasticSearchIndexType.indexName, putMappingResponse.isAcknowledged());
      } else {
        CreateIndexRequest request = new CreateIndexRequest(elasticSearchIndexType.indexName);
        //        request.source(Collections.singletonMap("properties", elasticSearchIndexMapping));
        CreateIndexResponse createIndexResponse = client.indices().create(request, RequestOptions.DEFAULT);
        LOG.info("{} Created {}", elasticSearchIndexType.indexName, createIndexResponse.isAcknowledged());
      }
      indexUtil.setIndexStatus(elasticSearchIndexType, IndexUtil.ElasticSearchIndexStatus.CREATED);
    } catch (Exception e) {
      indexUtil.setIndexStatus(elasticSearchIndexType, IndexUtil.ElasticSearchIndexStatus.FAILED);
      indexUtil.updateElasticSearchFailureStatus(
          indexUtil.getContext("Updating Index", elasticSearchIndexType.indexName),
          String.format(IndexUtil.REASON_TRACE, e.getMessage(), ExceptionUtils.getStackTrace(e)));
      LOG.error("Failed to update Elastic Search indexes due to", e);
    }
  }

  /** @param elasticSearchIndexType */
  @Override
  public void deleteIndex(ElasticSearchIndexDefinition.ElasticSearchIndexType elasticSearchIndexType) {
    try {
      GetIndexRequest gRequest = new GetIndexRequest(elasticSearchIndexType.indexName);
      gRequest.local(false);
      boolean exists = client.indices().exists(gRequest, RequestOptions.DEFAULT);
      if (exists) {
        DeleteIndexRequest request = new DeleteIndexRequest(elasticSearchIndexType.indexName);
        AcknowledgedResponse deleteIndexResponse = client.indices().delete(request, RequestOptions.DEFAULT);
        LOG.info("{} Deleted {}", elasticSearchIndexType.indexName, deleteIndexResponse.isAcknowledged());
      }
    } catch (IOException e) {
      indexUtil.updateElasticSearchFailureStatus(
          indexUtil.getContext("Deleting Index", elasticSearchIndexType.indexName),
          String.format(IndexUtil.REASON_TRACE, e.getMessage(), ExceptionUtils.getStackTrace(e)));
      LOG.error("Failed to delete Elastic Search indexes due to", e);
    }
  }

  /**
   * @param query
   * @param from
   * @param size
   * @param queryFilter
   * @param postFilter
   * @param fetchSource
   * @param trackTotalHits
   * @param sortFieldParam
   * @param deleted
   * @param index
   * @param sortOrder
   * @param includeSourceFields
   * @return
   * @throws IOException
   */
  @Override
  public String entityBuilder(
      String query,
      int from,
      int size,
      String queryFilter,
      String postFilter,
      boolean fetchSource,
      boolean trackTotalHits,
      String sortFieldParam,
      boolean deleted,
      String index,
      Object sortOrder,
      List<String> includeSourceFields)
      throws IOException {
    SearchSourceBuilder searchSourceBuilder;
    switch (index) {
      case "topic_search_index":
        searchSourceBuilder = buildTopicSearchBuilder(query, from, size);
        break;
      case "dashboard_search_index":
        searchSourceBuilder = buildDashboardSearchBuilder(query, from, size);
        break;
      case "pipeline_search_index":
        searchSourceBuilder = buildPipelineSearchBuilder(query, from, size);
        break;
      case "mlmodel_search_index":
        searchSourceBuilder = buildMlModelSearchBuilder(query, from, size);
        break;
      case "table_search_index":
        searchSourceBuilder = buildTableSearchBuilder(query, from, size);
        break;
      case "user_search_index":
        searchSourceBuilder = buildUserOrTeamSearchBuilder(query, from, size);
        break;
      case "team_search_index":
        searchSourceBuilder = buildUserOrTeamSearchBuilder(query, from, size);
        break;
      case "glossary_search_index":
        searchSourceBuilder = buildGlossaryTermSearchBuilder(query, from, size);
        break;
      case "tag_search_index":
        searchSourceBuilder = buildTagSearchBuilder(query, from, size);
        break;
      case "container_search_index":
        searchSourceBuilder = buildContainerSearchBuilder(query, from, size);
        break;
      case "query_search_index":
        searchSourceBuilder = buildQuerySearchBuilder(query, from, size);
        break;
      default:
        searchSourceBuilder = buildAggregateSearchBuilder(query, from, size);
        break;
    }
    if (!nullOrEmpty(queryFilter)) {
      try {
        XContentParser filterParser =
            XContentType.JSON
                .xContent()
                .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, queryFilter);
        QueryBuilder filter = SearchSourceBuilder.fromXContent(filterParser).query();
        BoolQueryBuilder newQuery = QueryBuilders.boolQuery().must(searchSourceBuilder.query()).filter(filter);
        searchSourceBuilder.query(newQuery);
      } catch (Exception ex) {
        LOG.warn("Error parsing query_filter from query parameters, ignoring filter", ex);
      }
    }

    if (!nullOrEmpty(postFilter)) {
      try {
        XContentParser filterParser =
            XContentType.JSON.xContent().createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, postFilter);
        QueryBuilder filter = SearchSourceBuilder.fromXContent(filterParser).query();
        searchSourceBuilder.postFilter(filter);
      } catch (Exception ex) {
        LOG.warn("Error parsing post_filter from query parameters, ignoring filter", ex);
      }
    }

    /* For backward-compatibility we continue supporting the deleted argument, this should be removed in future versions */
    searchSourceBuilder.query(
        QueryBuilders.boolQuery().must(searchSourceBuilder.query()).must(QueryBuilders.termQuery("deleted", deleted)));

    if (!nullOrEmpty(sortFieldParam)) {
      searchSourceBuilder.sort(sortFieldParam, (SortOrder) sortOrder);
    }

    /* for performance reasons ElasticSearch doesn't provide accurate hits
    if we enable trackTotalHits parameter it will try to match every result, count and return hits
    however in most cases for search results an approximate value is good enough.
    we are displaying total entity counts in landing page and explore page where we need the total count
    https://github.com/elastic/elasticsearch/issues/33028 */
    searchSourceBuilder.fetchSource(
        new FetchSourceContext(fetchSource, includeSourceFields.toArray(String[]::new), new String[] {}));

    if (trackTotalHits) {
      searchSourceBuilder.trackTotalHits(true);
    } else {
      searchSourceBuilder.trackTotalHitsUpTo(EntityBuilderConstant.MAX_RESULT_HITS);
    }

    searchSourceBuilder.timeout(new TimeValue(30, TimeUnit.SECONDS));
    String response =
        client.search(new SearchRequest(index).source(searchSourceBuilder), RequestOptions.DEFAULT).toString();

    return response;
  }

  /**
   * @param index
   * @param fieldName
   * @return
   */
  @Override
  public Response aggregate(String index, String fieldName) throws IOException {
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    searchSourceBuilder
        .aggregation(
            AggregationBuilders.terms(fieldName)
                .field(fieldName)
                .size(EntityBuilderConstant.MAX_AGGREGATE_SIZE)
                .order(BucketOrder.key(true)))
        .size(0);
    searchSourceBuilder.timeout(new TimeValue(30, TimeUnit.SECONDS));
    String response =
        client.search(new SearchRequest(index).source(searchSourceBuilder), RequestOptions.DEFAULT).toString();
    return Response.status(OK).entity(response).build();
  }

  @Override
  public Response suggest(
      String fieldName,
      String query,
      int size,
      String deleted,
      boolean fetchSource,
      List<String> includeSourceFields,
      String index)
      throws IOException {
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    CompletionSuggestionBuilder suggestionBuilder =
        SuggestBuilders.completionSuggestion(fieldName).prefix(query, Fuzziness.AUTO).size(size).skipDuplicates(true);
    if (fieldName.equalsIgnoreCase("suggest")) {
      suggestionBuilder.contexts(
          Collections.singletonMap(
              "deleted", Collections.singletonList(CategoryQueryContext.builder().setCategory(deleted).build())));
    }
    SuggestBuilder suggestBuilder = new SuggestBuilder();
    suggestBuilder.addSuggestion("metadata-suggest", suggestionBuilder);
    searchSourceBuilder
        .suggest(suggestBuilder)
        .timeout(new TimeValue(30, TimeUnit.SECONDS))
        .fetchSource(new FetchSourceContext(fetchSource, includeSourceFields.toArray(String[]::new), new String[] {}));
    SearchRequest searchRequest = new SearchRequest(index).source(searchSourceBuilder);
    SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
    Suggest suggest = searchResponse.getSuggest();
    return Response.status(OK).entity(suggest.toString()).build();
  }

  private static SearchSourceBuilder buildPipelineSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM)
            .field(FIELD_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION_NGRAM, 1.0f)
            .field(EntityBuilderConstant.DISPLAY_NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.DESCRIPTION, 1.0f)
            .field("tasks.name", 2.0f)
            .field("tasks.description", 1.0f)
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);
    HighlightBuilder.Field highlightPipelineName = new HighlightBuilder.Field(FIELD_DISPLAY_NAME);
    highlightPipelineName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightDescription = new HighlightBuilder.Field(EntityBuilderConstant.DESCRIPTION);
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightTasks = new HighlightBuilder.Field("tasks.name");
    highlightTasks.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightTaskDescriptions = new HighlightBuilder.Field("tasks.description");
    highlightTaskDescriptions.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder hb = new HighlightBuilder();
    hb.field(highlightDescription);
    hb.field(highlightPipelineName);
    hb.field(highlightTasks);
    hb.field(highlightTaskDescriptions);
    SearchSourceBuilder searchSourceBuilder = searchBuilder(queryBuilder, hb, from, size);
    return addAggregation(searchSourceBuilder);
  }

  private static SearchSourceBuilder buildMlModelSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM)
            .field(FIELD_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION_NGRAM, 1.0f)
            .field(EntityBuilderConstant.DISPLAY_NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.DESCRIPTION, 1.0f)
            .field("mlFeatures.name", 2.0f)
            .field("mlFeatures.description", 1.0f)
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);
    HighlightBuilder.Field highlightPipelineName = new HighlightBuilder.Field(FIELD_DISPLAY_NAME);
    highlightPipelineName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightDescription = new HighlightBuilder.Field(EntityBuilderConstant.DESCRIPTION);
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightTasks = new HighlightBuilder.Field("mlFeatures.name");
    highlightTasks.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightTaskDescriptions = new HighlightBuilder.Field("mlFeatures.description");
    highlightTaskDescriptions.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder hb = new HighlightBuilder();
    hb.field(highlightDescription);
    hb.field(highlightPipelineName);
    hb.field(highlightTasks);
    hb.field(highlightTaskDescriptions);
    SearchSourceBuilder searchSourceBuilder = searchBuilder(queryBuilder, hb, from, size);
    return addAggregation(searchSourceBuilder);
  }

  private static SearchSourceBuilder buildTopicSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM)
            .field(FIELD_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_NAME_NGRAM)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION_NGRAM, 1.0f)
            .field(EntityBuilderConstant.DISPLAY_NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION, 1.0f)
            .field(EntityBuilderConstant.ES_MESSAGE_SCHEMA_FIELD, 2.0f)
            .field("messageSchema.schemaFields.description", 1.0f)
            .field("messageSchema.schemaFields.children.name", 2.0f)
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);
    HighlightBuilder.Field highlightTopicName = new HighlightBuilder.Field(FIELD_DISPLAY_NAME);
    highlightTopicName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightDescription = new HighlightBuilder.Field(FIELD_DESCRIPTION);
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder hb = new HighlightBuilder();
    hb.field(highlightDescription);
    hb.field(highlightTopicName);
    hb.field(
        new HighlightBuilder.Field("messageSchema.schemaFields.description")
            .highlighterType(EntityBuilderConstant.UNIFIED));
    hb.field(
        new HighlightBuilder.Field("messageSchema.schemaFields.children.name")
            .highlighterType(EntityBuilderConstant.UNIFIED));
    SearchSourceBuilder searchSourceBuilder = searchBuilder(queryBuilder, hb, from, size);
    searchSourceBuilder.aggregation(
        AggregationBuilders.terms(EntityBuilderConstant.ES_MESSAGE_SCHEMA_FIELD)
            .field(EntityBuilderConstant.ES_MESSAGE_SCHEMA_FIELD));
    return addAggregation(searchSourceBuilder);
  }

  private static SearchSourceBuilder buildDashboardSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM)
            .field(FIELD_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_NAME_NGRAM)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION_NGRAM, 1.0f)
            .field(EntityBuilderConstant.DISPLAY_NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION, 1.0f)
            .field("charts.name", 2.0f)
            .field("charts.description", 1.0f)
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);
    HighlightBuilder.Field highlightDashboardName = new HighlightBuilder.Field(FIELD_DISPLAY_NAME);
    highlightDashboardName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightDescription = new HighlightBuilder.Field(FIELD_DESCRIPTION);
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightCharts = new HighlightBuilder.Field("charts.name");
    highlightCharts.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightChartDescriptions = new HighlightBuilder.Field("charts.description");
    highlightChartDescriptions.highlighterType(EntityBuilderConstant.UNIFIED);

    HighlightBuilder hb = new HighlightBuilder();
    hb.field(highlightDescription);
    hb.field(highlightDashboardName);
    hb.field(highlightCharts);
    hb.field(highlightChartDescriptions);

    SearchSourceBuilder searchSourceBuilder = searchBuilder(queryBuilder, hb, from, size);
    return addAggregation(searchSourceBuilder);
  }

  private static SearchSourceBuilder buildTableSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryStringBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM)
            .field(FIELD_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_NAME_NGRAM)
            .field(EntityBuilderConstant.DISPLAY_NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION, 1.0f)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION_NGRAM, 1.0f)
            .field("columns.name.keyword", 10.0f)
            .field("columns.name", 2.0f)
            .field("columns.name.ngram")
            .field("columns.displayName", 2.0f)
            .field("columns.displayName.ngram")
            .field("columns.description", 1.0f)
            .field("columns.children.name", 2.0f)
            .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);
    FieldValueFactorFunctionBuilder boostScoreBuilder =
        ScoreFunctionBuilders.fieldValueFactorFunction("usageSummary.weeklyStats.count").missing(0).factor(0.2f);
    FunctionScoreQueryBuilder.FilterFunctionBuilder[] functions =
        new FunctionScoreQueryBuilder.FilterFunctionBuilder[] {
          new FunctionScoreQueryBuilder.FilterFunctionBuilder(boostScoreBuilder)
        };
    FunctionScoreQueryBuilder queryBuilder = QueryBuilders.functionScoreQuery(queryStringBuilder, functions);
    queryBuilder.boostMode(CombineFunction.SUM);
    HighlightBuilder.Field highlightTableName = new HighlightBuilder.Field(FIELD_DISPLAY_NAME);
    highlightTableName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightDescription = new HighlightBuilder.Field(EntityBuilderConstant.DESCRIPTION);
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder hb = new HighlightBuilder();
    HighlightBuilder.Field highlightColumns = new HighlightBuilder.Field("columns.name");
    highlightColumns.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightColumnDescriptions = new HighlightBuilder.Field("columns.description");
    highlightColumnDescriptions.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightColumnChildren = new HighlightBuilder.Field("columns.children.name");
    highlightColumnDescriptions.highlighterType(EntityBuilderConstant.UNIFIED);
    hb.field(highlightDescription);
    hb.field(highlightTableName);
    hb.field(highlightColumns);
    hb.field(highlightColumnDescriptions);
    hb.field(highlightColumnChildren);
    hb.preTags(EntityBuilderConstant.PRE_TAG);
    hb.postTags(EntityBuilderConstant.POST_TAG);
    SearchSourceBuilder searchSourceBuilder =
        new SearchSourceBuilder().query(queryBuilder).highlighter(hb).from(from).size(size);
    searchSourceBuilder.aggregation(AggregationBuilders.terms("database.name.keyword").field("database.name.keyword"));
    searchSourceBuilder.aggregation(
        AggregationBuilders.terms("databaseSchema.name.keyword").field("databaseSchema.name.keyword"));
    return addAggregation(searchSourceBuilder);
  }

  private static SearchSourceBuilder buildUserOrTeamSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(EntityBuilderConstant.DISPLAY_NAME, 3.0f)
            .field(EntityBuilderConstant.DISPLAY_NAME_KEYWORD, 5.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM)
            .field(FIELD_NAME, 2.0f)
            .field(EntityBuilderConstant.NAME_KEYWORD, 3.0f)
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);
    return searchBuilder(queryBuilder, null, from, size);
  }

  private static SearchSourceBuilder buildGlossaryTermSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME, 10.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM, 1.0f)
            .field(FIELD_NAME, 10.0f)
            .field(EntityBuilderConstant.NAME_KEYWORD, 10.0f)
            .field(EntityBuilderConstant.DISPLAY_NAME_KEYWORD, 10.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME, 10.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM)
            .field("synonyms", 5.0f)
            .field("synonyms.ngram")
            .field(EntityBuilderConstant.DESCRIPTION, 3.0f)
            .field("glossary.name", 5.0f)
            .field("glossary.displayName", 5.0f)
            .field("glossary.displayName.ngram")
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);

    HighlightBuilder.Field highlightGlossaryName = new HighlightBuilder.Field(FIELD_NAME);
    highlightGlossaryName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightGlossaryDisplayName = new HighlightBuilder.Field(FIELD_DISPLAY_NAME);
    highlightGlossaryDisplayName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightDescription = new HighlightBuilder.Field(FIELD_DESCRIPTION);
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightSynonym = new HighlightBuilder.Field("synonyms");
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder hb = new HighlightBuilder();
    hb.field(highlightDescription);
    hb.field(highlightGlossaryName);
    hb.field(highlightGlossaryDisplayName);
    hb.field(highlightSynonym);

    hb.preTags(EntityBuilderConstant.PRE_TAG);
    hb.postTags(EntityBuilderConstant.POST_TAG);
    SearchSourceBuilder searchSourceBuilder =
        new SearchSourceBuilder().query(queryBuilder).highlighter(hb).from(from).size(size);
    searchSourceBuilder
        .aggregation(
            AggregationBuilders.terms(EntityBuilderConstant.ES_TAG_FQN_FIELD)
                .field(EntityBuilderConstant.ES_TAG_FQN_FIELD)
                .size(EntityBuilderConstant.MAX_AGGREGATE_SIZE))
        .aggregation(AggregationBuilders.terms("glossary.name.keyword").field("glossary.name.keyword"));
    return searchSourceBuilder;
  }

  private static SearchSourceBuilder buildTagSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(FIELD_NAME, 10.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME, 10.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM, 1.0f)
            .field(EntityBuilderConstant.DESCRIPTION, 3.0f)
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);

    HighlightBuilder.Field highlightTagName = new HighlightBuilder.Field(FIELD_NAME);
    highlightTagName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightTagDisplayName = new HighlightBuilder.Field(FIELD_DISPLAY_NAME);
    highlightTagDisplayName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightDescription = new HighlightBuilder.Field(FIELD_DESCRIPTION);
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder hb = new HighlightBuilder();
    hb.field(highlightTagDisplayName);
    hb.field(highlightDescription);
    hb.field(highlightTagName);
    hb.preTags(EntityBuilderConstant.PRE_TAG);
    hb.postTags(EntityBuilderConstant.POST_TAG);
    return searchBuilder(queryBuilder, hb, from, size);
  }

  private static SearchSourceBuilder buildContainerSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM)
            .field(FIELD_NAME, 15.0f)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION, 1.0f)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION_NGRAM, 1.0f)
            .field(EntityBuilderConstant.DISPLAY_NAME_KEYWORD, 25.0f)
            .field(EntityBuilderConstant.NAME_KEYWORD, 25.0f)
            .field("dataModel.columns.name", 2.0f)
            .field("dataModel.columns.name.keyword", 10.0f)
            .field("dataModel.columns.name.ngram")
            .field("dataModel.columns.displayName", 2.0f)
            .field("dataModel.columns.displayName.ngram")
            .field("dataModel.columns.description", 1.0f)
            .field("dataModel.columns.children.name", 2.0f)
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);
    HighlightBuilder.Field highlightContainerName = new HighlightBuilder.Field(FIELD_DISPLAY_NAME);
    highlightContainerName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightDescription = new HighlightBuilder.Field(EntityBuilderConstant.DESCRIPTION);
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder hb = new HighlightBuilder();
    HighlightBuilder.Field highlightColumns = new HighlightBuilder.Field("dataModel.columns.name");
    highlightColumns.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightColumnDescriptions = new HighlightBuilder.Field("dataModel.columns.description");
    highlightColumnDescriptions.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightColumnChildren = new HighlightBuilder.Field("dataModel.columns.children.name");
    highlightColumnDescriptions.highlighterType(EntityBuilderConstant.UNIFIED);
    hb.field(highlightDescription);
    hb.field(highlightContainerName);
    hb.field(highlightColumns);
    hb.field(highlightColumnDescriptions);
    hb.field(highlightColumnChildren);
    hb.preTags(EntityBuilderConstant.PRE_TAG);
    hb.postTags(EntityBuilderConstant.POST_TAG);
    SearchSourceBuilder searchSourceBuilder =
        new SearchSourceBuilder().query(queryBuilder).highlighter(hb).from(from).size(size);
    return addAggregation(searchSourceBuilder);
  }

  private static SearchSourceBuilder buildQuerySearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder =
        QueryBuilders.queryStringQuery(query)
            .field(EntityBuilderConstant.DISPLAY_NAME, 10.0f)
            .field(EntityBuilderConstant.FIELD_DISPLAY_NAME_NGRAM)
            .field(EntityBuilderConstant.QUERY, 10.0f)
            .field(EntityBuilderConstant.QUERY_NGRAM)
            .field(EntityBuilderConstant.DESCRIPTION, 1.0f)
            .field(EntityBuilderConstant.FIELD_DESCRIPTION_NGRAM, 1.0f)
            .defaultOperator(Operator.AND)
            .fuzziness(Fuzziness.AUTO);

    HighlightBuilder.Field highlightGlossaryName = new HighlightBuilder.Field(EntityBuilderConstant.DISPLAY_NAME);
    highlightGlossaryName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightDescription = new HighlightBuilder.Field(FIELD_DESCRIPTION);
    highlightDescription.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder.Field highlightQuery = new HighlightBuilder.Field(EntityBuilderConstant.QUERY);
    highlightGlossaryName.highlighterType(EntityBuilderConstant.UNIFIED);
    HighlightBuilder hb = new HighlightBuilder();
    hb.field(highlightDescription);
    hb.field(highlightGlossaryName);
    hb.field(highlightQuery);
    hb.preTags(EntityBuilderConstant.PRE_TAG);
    hb.postTags(EntityBuilderConstant.POST_TAG);
    return searchBuilder(queryBuilder, hb, from, size);
  }

  private static SearchSourceBuilder buildAggregateSearchBuilder(String query, int from, int size) {
    QueryStringQueryBuilder queryBuilder = QueryBuilders.queryStringQuery(query).lenient(true);
    SearchSourceBuilder searchSourceBuilder = searchBuilder(queryBuilder, null, from, size);
    return addAggregation(searchSourceBuilder);
  }

  private static SearchSourceBuilder addAggregation(SearchSourceBuilder builder) {
    builder
        .aggregation(
            AggregationBuilders.terms("serviceType")
                .field("serviceType")
                .size(EntityBuilderConstant.MAX_AGGREGATE_SIZE))
        .aggregation(
            AggregationBuilders.terms("service.name.keyword")
                .field("service.name.keyword")
                .size(EntityBuilderConstant.MAX_AGGREGATE_SIZE))
        .aggregation(
            AggregationBuilders.terms("entityType").field("entityType").size(EntityBuilderConstant.MAX_AGGREGATE_SIZE))
        .aggregation(AggregationBuilders.terms("tier.tagFQN").field("tier.tagFQN"));

    return builder;
  }

  private static SearchSourceBuilder searchBuilder(QueryBuilder queryBuilder, HighlightBuilder hb, int from, int size) {
    SearchSourceBuilder builder = new SearchSourceBuilder().query(queryBuilder).from(from).size(size);
    if (hb != null) {
      hb.preTags(EntityBuilderConstant.PRE_TAG);
      hb.postTags(EntityBuilderConstant.POST_TAG);
      builder.highlighter(hb);
    }
    return builder;
  }

  @Override
  public String getSearchType() {
    return SEARCH_TYPE;
  }

  @Override
  public void updateEntity(ChangeEvent event) throws IOException {
    String entityType = event.getEntityType();
    ElasticSearchIndexDefinition.ElasticSearchIndexType indexType =
        ElasticSearchIndexDefinition.getIndexMappingByEntityType(entityType);
    UpdateRequest updateRequest = new UpdateRequest(indexType.indexName, event.getEntityId().toString());
    ElasticSearchIndex index;

    switch (event.getEventType()) {
      case ENTITY_CREATED:
        index = ElasticSearchIndexFactory.buildIndex(entityType, event.getEntity());
        updateRequest.doc(JsonUtils.pojoToJson(index.buildESDoc()), XContentType.JSON);
        updateRequest.docAsUpsert(true);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_UPDATED:
        if (Objects.equals(event.getCurrentVersion(), event.getPreviousVersion())) {
          updateRequest = applyChangeEvent(event);
        } else {
          index = ElasticSearchIndexFactory.buildIndex(entityType, event.getEntity());
          scriptedUpsert(index.buildESDoc(), updateRequest);
        }
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_SOFT_DELETED:
        softDeleteEntity(updateRequest);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_DELETED:
        DeleteRequest deleteRequest = new DeleteRequest(indexType.indexName, event.getEntityId().toString());
        deleteEntityFromElasticSearch(deleteRequest);
        break;
    }
  }

  @Override
  public void updateUser(ChangeEvent event) throws IOException {
    UpdateRequest updateRequest =
        new UpdateRequest(
            ElasticSearchIndexDefinition.ElasticSearchIndexType.USER_SEARCH_INDEX.indexName,
            event.getEntityId().toString());
    UserIndex userIndex;

    switch (event.getEventType()) {
      case ENTITY_CREATED:
        userIndex = new UserIndex((User) event.getEntity());
        updateRequest.doc(JsonUtils.pojoToJson(userIndex.buildESDoc()), XContentType.JSON);
        updateRequest.docAsUpsert(true);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_UPDATED:
        userIndex = new UserIndex((User) event.getEntity());
        scriptedUserUpsert(userIndex.buildESDoc(), updateRequest);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_SOFT_DELETED:
        softDeleteEntity(updateRequest);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_DELETED:
        DeleteRequest deleteRequest =
            new DeleteRequest(
                ElasticSearchIndexDefinition.ElasticSearchIndexType.USER_SEARCH_INDEX.indexName,
                event.getEntityId().toString());
        deleteEntityFromElasticSearch(deleteRequest);
        break;
    }
  }

  @Override
  public void updateTeam(ChangeEvent event) throws IOException {
    UpdateRequest updateRequest =
        new UpdateRequest(
            ElasticSearchIndexDefinition.ElasticSearchIndexType.TEAM_SEARCH_INDEX.indexName,
            event.getEntityId().toString());
    TeamIndex teamIndex;
    switch (event.getEventType()) {
      case ENTITY_CREATED:
        teamIndex = new TeamIndex((Team) event.getEntity());
        updateRequest.doc(JsonUtils.pojoToJson(teamIndex.buildESDoc()), XContentType.JSON);
        updateRequest.docAsUpsert(true);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_UPDATED:
        teamIndex = new TeamIndex((Team) event.getEntity());
        scriptedTeamUpsert(teamIndex.buildESDoc(), updateRequest);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_SOFT_DELETED:
        softDeleteEntity(updateRequest);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_DELETED:
        DeleteRequest deleteRequest =
            new DeleteRequest(
                ElasticSearchIndexDefinition.ElasticSearchIndexType.TEAM_SEARCH_INDEX.indexName,
                event.getEntityId().toString());
        deleteEntityFromElasticSearch(deleteRequest);
        break;
    }
  }

  @Override
  public void updateGlossaryTerm(ChangeEvent event) throws IOException {
    UpdateRequest updateRequest =
        new UpdateRequest(
            ElasticSearchIndexDefinition.ElasticSearchIndexType.GLOSSARY_SEARCH_INDEX.indexName,
            event.getEntityId().toString());
    GlossaryTermIndex glossaryTermIndex;

    switch (event.getEventType()) {
      case ENTITY_CREATED:
        glossaryTermIndex = new GlossaryTermIndex((GlossaryTerm) event.getEntity());
        updateRequest.doc(JsonUtils.pojoToJson(glossaryTermIndex.buildESDoc()), XContentType.JSON);
        updateRequest.docAsUpsert(true);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_UPDATED:
        glossaryTermIndex = new GlossaryTermIndex((GlossaryTerm) event.getEntity());
        scriptedUpsert(glossaryTermIndex.buildESDoc(), updateRequest);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_SOFT_DELETED:
        softDeleteEntity(updateRequest);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_DELETED:
        DeleteByQueryRequest request =
            new DeleteByQueryRequest(
                ElasticSearchIndexDefinition.ElasticSearchIndexType.GLOSSARY_SEARCH_INDEX.indexName);
        new DeleteRequest(
            ElasticSearchIndexDefinition.ElasticSearchIndexType.GLOSSARY_SEARCH_INDEX.indexName,
            event.getEntityId().toString());
        GlossaryTerm glossaryTerm = (GlossaryTerm) event.getEntity();
        request.setQuery(
            QueryBuilders.boolQuery()
                .should(QueryBuilders.matchQuery("id", glossaryTerm.getId().toString()))
                .should(QueryBuilders.matchQuery("parent.id", glossaryTerm.getId().toString())));
        deleteEntityFromElasticSearchByQuery(request);
        break;
    }
  }

  @Override
  public void updateGlossary(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      Glossary glossary = (Glossary) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(ElasticSearchIndexDefinition.ElasticSearchIndexType.GLOSSARY_SEARCH_INDEX.indexName);
      request.setQuery(
          QueryBuilders.boolQuery().should(QueryBuilders.matchQuery("glossary.id", glossary.getId().toString())));
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  @Override
  public void updateTag(ChangeEvent event) throws IOException {
    UpdateRequest updateRequest =
        new UpdateRequest(
            ElasticSearchIndexDefinition.ElasticSearchIndexType.TAG_SEARCH_INDEX.indexName,
            event.getEntityId().toString());
    TagIndex tagIndex;

    switch (event.getEventType()) {
      case ENTITY_CREATED:
        tagIndex = new TagIndex((Tag) event.getEntity());
        updateRequest.doc(JsonUtils.pojoToJson(tagIndex.buildESDoc()), XContentType.JSON);
        updateRequest.docAsUpsert(true);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_UPDATED:
        if (Objects.equals(event.getCurrentVersion(), event.getPreviousVersion())) {
          updateRequest = applyChangeEvent(event);
        } else {
          tagIndex = new TagIndex((Tag) event.getEntity());
          scriptedUpsert(tagIndex.buildESDoc(), updateRequest);
        }
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_SOFT_DELETED:
        softDeleteEntity(updateRequest);
        updateElasticSearch(updateRequest);
        break;
      case ENTITY_DELETED:
        DeleteRequest deleteRequest =
            new DeleteRequest(
                ElasticSearchIndexDefinition.ElasticSearchIndexType.TAG_SEARCH_INDEX.indexName,
                event.getEntityId().toString());
        deleteEntityFromElasticSearch(deleteRequest);

        String[] indexes =
            new String[] {
              ElasticSearchIndexDefinition.ElasticSearchIndexType.TABLE_SEARCH_INDEX.indexName,
              ElasticSearchIndexDefinition.ElasticSearchIndexType.TOPIC_SEARCH_INDEX.indexName,
              ElasticSearchIndexDefinition.ElasticSearchIndexType.DASHBOARD_SEARCH_INDEX.indexName,
              ElasticSearchIndexDefinition.ElasticSearchIndexType.PIPELINE_SEARCH_INDEX.indexName,
              ElasticSearchIndexDefinition.ElasticSearchIndexType.GLOSSARY_SEARCH_INDEX.indexName,
              ElasticSearchIndexDefinition.ElasticSearchIndexType.MLMODEL_SEARCH_INDEX.indexName
            };
        BulkRequest request = new BulkRequest();
        SearchRequest searchRequest;
        SearchResponse response;
        int batchSize = 50;
        int totalHits;
        int currentHits = 0;

        do {
          searchRequest =
              searchRequest(indexes, "tags.tagFQN", event.getEntityFullyQualifiedName(), batchSize, currentHits);
          response = client.search(searchRequest, RequestOptions.DEFAULT);
          totalHits = (int) response.getHits().getTotalHits().value;
          for (SearchHit hit : response.getHits()) {
            Map<String, Object> sourceAsMap = hit.getSourceAsMap();
            List<TagLabel> listTags = (List<TagLabel>) sourceAsMap.get("tags");
            Script script = generateTagScript(listTags);
            if (!script.toString().isEmpty()) {
              request.add(
                  updateRequests(sourceAsMap.get("entityType").toString(), sourceAsMap.get("id").toString(), script));
            }
          }
          currentHits += response.getHits().getHits().length;
        } while (currentHits < totalHits);
        if (request.numberOfActions() > 0) {
          client.bulk(request, RequestOptions.DEFAULT);
        }
    }
  }

  @Override
  public void updateDatabase(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      Database database = (Database) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(ElasticSearchIndexDefinition.ElasticSearchIndexType.TABLE_SEARCH_INDEX.indexName);
      BoolQueryBuilder queryBuilder = new BoolQueryBuilder();
      queryBuilder.must(new TermQueryBuilder(UpdateSearchEventsConstant.DATABASE_NAME, database.getName()));
      queryBuilder.must(new TermQueryBuilder(UpdateSearchEventsConstant.SERVICE_NAME, database.getService().getName()));
      request.setQuery(queryBuilder);
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  @Override
  public void updateDatabaseSchema(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      DatabaseSchema databaseSchema = (DatabaseSchema) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(ElasticSearchIndexDefinition.ElasticSearchIndexType.TABLE_SEARCH_INDEX.indexName);
      BoolQueryBuilder queryBuilder = new BoolQueryBuilder();
      queryBuilder.must(new TermQueryBuilder("databaseSchema.name", databaseSchema.getName()));
      queryBuilder.must(
          new TermQueryBuilder(UpdateSearchEventsConstant.DATABASE_NAME, databaseSchema.getDatabase().getName()));
      request.setQuery(queryBuilder);
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  @Override
  public void updateDatabaseService(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      DatabaseService databaseService = (DatabaseService) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(ElasticSearchIndexDefinition.ElasticSearchIndexType.TABLE_SEARCH_INDEX.indexName);
      request.setQuery(new TermQueryBuilder(UpdateSearchEventsConstant.SERVICE_NAME, databaseService.getName()));
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  @Override
  public void updatePipelineService(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      PipelineService pipelineService = (PipelineService) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(ElasticSearchIndexDefinition.ElasticSearchIndexType.PIPELINE_SEARCH_INDEX.indexName);
      request.setQuery(new TermQueryBuilder(UpdateSearchEventsConstant.SERVICE_NAME, pipelineService.getName()));
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  @Override
  public void updateMlModelService(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      MlModelService mlModelService = (MlModelService) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(ElasticSearchIndexDefinition.ElasticSearchIndexType.MLMODEL_SEARCH_INDEX.indexName);
      request.setQuery(new TermQueryBuilder(UpdateSearchEventsConstant.SERVICE_NAME, mlModelService.getName()));
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  @Override
  public void updateStorageService(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      StorageService storageService = (StorageService) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(
              ElasticSearchIndexDefinition.ElasticSearchIndexType.CONTAINER_SEARCH_INDEX.indexName);
      request.setQuery(new TermQueryBuilder(UpdateSearchEventsConstant.SERVICE_NAME, storageService.getName()));
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  @Override
  public void updateMessagingService(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      MessagingService messagingService = (MessagingService) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(ElasticSearchIndexDefinition.ElasticSearchIndexType.TOPIC_SEARCH_INDEX.indexName);
      request.setQuery(new TermQueryBuilder(UpdateSearchEventsConstant.SERVICE_NAME, messagingService.getName()));
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  @Override
  public void updateDashboardService(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      DashboardService dashboardService = (DashboardService) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(
              ElasticSearchIndexDefinition.ElasticSearchIndexType.DASHBOARD_SEARCH_INDEX.indexName);
      request.setQuery(new TermQueryBuilder(UpdateSearchEventsConstant.SERVICE_NAME, dashboardService.getName()));
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  @Override
  public void updateClassification(ChangeEvent event) throws IOException {
    if (event.getEventType() == EventType.ENTITY_DELETED) {
      Classification classification = (Classification) event.getEntity();
      DeleteByQueryRequest request =
          new DeleteByQueryRequest(ElasticSearchIndexDefinition.ElasticSearchIndexType.TAG_SEARCH_INDEX.indexName);
      String fqnMatch = classification.getName() + ".*";
      request.setQuery(new WildcardQueryBuilder("fullyQualifiedName", fqnMatch));
      deleteEntityFromElasticSearchByQuery(request);
    }
  }

  /** */
  @Override
  public void close() {
    try {
      this.client.close();
    } catch (Exception e) {
      LOG.error("Failed to close elastic search", e);
    }
  }

  private void scriptedUpsert(Object doc, UpdateRequest updateRequest) {
    String scriptTxt = "for (k in params.keySet()) { ctx._source.put(k, params.get(k)) }";
    Script script = new Script(ScriptType.INLINE, Script.DEFAULT_SCRIPT_LANG, scriptTxt, JsonUtils.getMap(doc));
    updateRequest.script(script);
    updateRequest.scriptedUpsert(true);
    updateRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
  }

  private void scriptedUserUpsert(Object index, UpdateRequest updateRequest) {
    String scriptTxt = "for (k in params.keySet()) {ctx._source.put(k, params.get(k)) }";
    Map<String, Object> doc = JsonUtils.getMap(index);
    Script script = new Script(ScriptType.INLINE, Script.DEFAULT_SCRIPT_LANG, scriptTxt, doc);
    updateRequest.script(script);
    updateRequest.scriptedUpsert(true);
  }

  private void scriptedTeamUpsert(Object index, UpdateRequest updateRequest) {
    String scriptTxt = "for (k in params.keySet()) { ctx._source.put(k, params.get(k)) }";
    Map<String, Object> doc = JsonUtils.getMap(index);
    Script script = new Script(ScriptType.INLINE, Script.DEFAULT_SCRIPT_LANG, scriptTxt, doc);
    updateRequest.script(script);
    updateRequest.scriptedUpsert(true);
  }

  private void softDeleteEntity(UpdateRequest updateRequest) {
    String scriptTxt = "ctx._source.deleted=true";
    Script script = new Script(ScriptType.INLINE, Script.DEFAULT_SCRIPT_LANG, scriptTxt, new HashMap<>());
    updateRequest.script(script);
  }

  private void updateElasticSearch(UpdateRequest updateRequest) throws IOException {
    if (updateRequest != null) {
      LOG.debug(UpdateSearchEventsConstant.SENDING_REQUEST_TO_ELASTIC_SEARCH, updateRequest);
      client.update(updateRequest, RequestOptions.DEFAULT);
    }
  }

  private void deleteEntityFromElasticSearch(DeleteRequest deleteRequest) throws IOException {
    if (deleteRequest != null) {
      LOG.debug(UpdateSearchEventsConstant.SENDING_REQUEST_TO_ELASTIC_SEARCH, deleteRequest);
      deleteRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL);
      client.delete(deleteRequest, RequestOptions.DEFAULT);
    }
  }

  private void deleteEntityFromElasticSearchByQuery(DeleteByQueryRequest deleteRequest) throws IOException {
    if (deleteRequest != null) {
      LOG.debug(UpdateSearchEventsConstant.SENDING_REQUEST_TO_ELASTIC_SEARCH, deleteRequest);
      deleteRequest.setRefresh(true);
      client.deleteByQuery(deleteRequest, RequestOptions.DEFAULT);
    }
  }

  private UpdateRequest applyChangeEvent(ChangeEvent event) {
    String entityType = event.getEntityType();
    ElasticSearchIndexDefinition.ElasticSearchIndexType esIndexType =
        ElasticSearchIndexDefinition.getIndexMappingByEntityType(entityType);
    UUID entityId = event.getEntityId();
    ChangeDescription changeDescription = event.getChangeDescription();

    List<FieldChange> fieldsAdded = changeDescription.getFieldsAdded();
    StringBuilder scriptTxt = new StringBuilder();
    Map<String, Object> fieldAddParams = new HashMap<>();
    fieldAddParams.put("updatedAt", event.getTimestamp());
    scriptTxt.append("ctx._source.updatedAt=params.updatedAt;");
    for (FieldChange fieldChange : fieldsAdded) {
      if (fieldChange.getName().equalsIgnoreCase(FIELD_FOLLOWERS)) {
        @SuppressWarnings("unchecked")
        List<EntityReference> entityReferences = (List<EntityReference>) fieldChange.getNewValue();
        List<String> newFollowers = new ArrayList<>();
        for (EntityReference follower : entityReferences) {
          newFollowers.add(follower.getId().toString());
        }
        fieldAddParams.put(fieldChange.getName(), newFollowers);
        scriptTxt.append("ctx._source.followers.addAll(params.followers);");
      }
    }

    for (FieldChange fieldChange : changeDescription.getFieldsDeleted()) {
      if (fieldChange.getName().equalsIgnoreCase(FIELD_FOLLOWERS)) {
        @SuppressWarnings("unchecked")
        List<EntityReference> entityReferences = (List<EntityReference>) fieldChange.getOldValue();
        for (EntityReference follower : entityReferences) {
          fieldAddParams.put(fieldChange.getName(), follower.getId().toString());
        }
        scriptTxt.append("ctx._source.followers.removeAll(Collections.singleton(params.followers));");
      }
    }

    for (FieldChange fieldChange : changeDescription.getFieldsUpdated()) {
      if (fieldChange.getName().equalsIgnoreCase(FIELD_USAGE_SUMMARY)) {
        UsageDetails usageSummary = (UsageDetails) fieldChange.getNewValue();
        fieldAddParams.put(fieldChange.getName(), JsonUtils.getMap(usageSummary));
        scriptTxt.append("ctx._source.usageSummary = params.usageSummary;");
      }
      if (event.getEntityType().equals(QUERY) && fieldChange.getName().equalsIgnoreCase("queryUsedIn")) {
        fieldAddParams.put(
            fieldChange.getName(),
            JsonUtils.convertValue(
                fieldChange.getNewValue(), new TypeReference<List<LinkedHashMap<String, String>>>() {}));
        scriptTxt.append("ctx._source.queryUsedIn = params.queryUsedIn;");
      }
      if (fieldChange.getName().equalsIgnoreCase("votes")) {
        Map<String, Object> doc = JsonUtils.getMap(event.getEntity());
        fieldAddParams.put(fieldChange.getName(), doc.get("votes"));
        scriptTxt.append("ctx._source.votes = params.votes;");
      }
    }

    if (!scriptTxt.toString().isEmpty()) {
      Script script = new Script(ScriptType.INLINE, Script.DEFAULT_SCRIPT_LANG, scriptTxt.toString(), fieldAddParams);
      UpdateRequest updateRequest = new UpdateRequest(esIndexType.indexName, entityId.toString());
      updateRequest.script(script);
      return updateRequest;
    } else {
      return null;
    }
  }

  private SearchRequest searchRequest(String[] indexes, String field, String value, int batchSize, int from) {
    SearchRequest searchRequest = new SearchRequest(indexes);
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    searchSourceBuilder.query(QueryBuilders.matchQuery(field, value));
    searchSourceBuilder.from(from);
    searchSourceBuilder.size(batchSize);
    searchSourceBuilder.timeout(new TimeValue(60, TimeUnit.SECONDS));
    searchRequest.source(searchSourceBuilder);
    return searchRequest;
  }

  private Script generateTagScript(List<TagLabel> listTags) {
    StringBuilder scriptTxt = new StringBuilder();
    Map<String, Object> fieldRemoveParams = new HashMap<>();
    fieldRemoveParams.put("tags", listTags);
    scriptTxt.append("ctx._source.tags=params.tags;");
    scriptTxt.append("ctx._source.tags.removeAll(params.tags);");
    fieldRemoveParams.put("tags", listTags);
    return new Script(ScriptType.INLINE, Script.DEFAULT_SCRIPT_LANG, scriptTxt.toString(), fieldRemoveParams);
  }

  private UpdateRequest updateRequests(String entityType, String entityId, Script script) {
    return new UpdateRequest(IndexUtil.ENTITY_TYPE_TO_INDEX_MAP.get(entityType), entityId).script(script);
  }

  /**
   * @param data
   * @param options
   * @return
   * @throws IOException
   */
  @Override
  public BulkResponse bulk(BulkRequest data, RequestOptions options) throws IOException {
    BulkResponse response = client.bulk(data, RequestOptions.DEFAULT);
    return response;
  }

  /**
   * @param response
   * @return
   */
  @Override
  public int getSuccessFromBulkResponse(BulkResponse response) {
    int success = 0;
    for (BulkItemResponse bulkItemResponse : response) {
      if (!bulkItemResponse.isFailed()) {
        success++;
      }
    }
    return success;
  }
  /*
   */
  /**
   * @param
   * @param team
   * @param scheduleTime
   * @param currentTime
   * @param chartType
   * @param indexName
   * @return
   */
  @Override
  public TreeMap<Long, List<Object>> getSortedDate(
      String team,
      Long scheduleTime,
      Long currentTime,
      DataInsightChartResult.DataInsightChartType chartType,
      String indexName)
      throws IOException, ParseException {
    SearchRequest searchRequestTotalAssets =
        buildSearchRequest(scheduleTime, currentTime, null, team, chartType, indexName);
    SearchResponse searchResponseTotalAssets = client.search(searchRequestTotalAssets, RequestOptions.DEFAULT);
    DataInsightChartResult processedDataTotalAssets =
        processDataInsightChartResult(searchResponseTotalAssets, chartType);
    TreeMap<Long, List<Object>> dateWithDataMap = new TreeMap<>();
    for (Object data : processedDataTotalAssets.getData()) {
      DataInsightInterface convertedData = (DataInsightInterface) data;
      Long timestamp = convertedData.getTimestamp();
      List<Object> totalEntitiesByTypeList = new ArrayList<>();
      if (dateWithDataMap.containsKey(timestamp)) {
        totalEntitiesByTypeList = dateWithDataMap.get(timestamp);
      }
      totalEntitiesByTypeList.add(convertedData);
      dateWithDataMap.put(timestamp, totalEntitiesByTypeList);
    }
    return dateWithDataMap;
  }

  /**
   * @param startTs
   * @param endTs
   * @param tier
   * @param team
   * @param dataInsightChartName
   * @param dataReportIndex
   * @return
   * @throws IOException
   * @throws ParseException
   */
  @Override
  public Response listDataInsightChartResult(
      Long startTs,
      Long endTs,
      String tier,
      String team,
      DataInsightChartResult.DataInsightChartType dataInsightChartName,
      String dataReportIndex)
      throws IOException, ParseException {
    SearchRequest searchRequest = buildSearchRequest(startTs, endTs, tier, team, dataInsightChartName, dataReportIndex);
    SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
    return Response.status(OK).entity(processDataInsightChartResult(searchResponse, dataInsightChartName)).build();
  }

  private static DataInsightChartResult processDataInsightChartResult(
      SearchResponse searchResponse, DataInsightChartResult.DataInsightChartType dataInsightChartName)
      throws ParseException {
    DataInsightAggregatorInterface processor =
        createDataAggregator(searchResponse.getAggregations(), dataInsightChartName);
    return processor.process();
  }

  private static DataInsightAggregatorInterface createDataAggregator(
      Aggregations aggregations, DataInsightChartResult.DataInsightChartType dataInsightChartType)
      throws IllegalArgumentException {
    switch (dataInsightChartType) {
      case PERCENTAGE_OF_ENTITIES_WITH_DESCRIPTION_BY_TYPE:
        return new EsEntitiesDescriptionAggregator(aggregations, dataInsightChartType);
      case PERCENTAGE_OF_ENTITIES_WITH_OWNER_BY_TYPE:
        return new EsEntitiesOwnerAggregator(aggregations, dataInsightChartType);
      case TOTAL_ENTITIES_BY_TYPE:
        return new EsTotalEntitiesAggregator(aggregations, dataInsightChartType);
      case TOTAL_ENTITIES_BY_TIER:
        return new EsTotalEntitiesByTierAggregator(aggregations, dataInsightChartType);
      case DAILY_ACTIVE_USERS:
        return new EsDailyActiveUsersAggregator(aggregations, dataInsightChartType);
      case PAGE_VIEWS_BY_ENTITIES:
        return new EsPageViewsByEntitiesAggregator(aggregations, dataInsightChartType);
      case MOST_ACTIVE_USERS:
        return new EsMostActiveUsersAggregator(aggregations, dataInsightChartType);
      case MOST_VIEWED_ENTITIES:
        return new EsMostViewedEntitiesAggregator(aggregations, dataInsightChartType);
      default:
        throw new IllegalArgumentException(
            String.format("No processor found for chart Type %s ", dataInsightChartType));
    }
  }

  private static SearchRequest buildSearchRequest(
      Long startTs,
      Long endTs,
      String tier,
      String team,
      DataInsightChartResult.DataInsightChartType dataInsightChartName,
      String dataReportIndex) {
    SearchSourceBuilder searchSourceBuilder =
        buildQueryFilter(startTs, endTs, tier, team, dataInsightChartName.value());
    AggregationBuilder aggregationBuilder = buildQueryAggregation(dataInsightChartName);
    searchSourceBuilder.aggregation(aggregationBuilder);
    searchSourceBuilder.timeout(new TimeValue(30, TimeUnit.SECONDS));

    SearchRequest searchRequest = new SearchRequest(dataReportIndex);
    searchRequest.source(searchSourceBuilder);
    return searchRequest;
  }

  private static SearchSourceBuilder buildQueryFilter(
      Long startTs, Long endTs, String tier, String team, String dataInsightChartName) {

    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    BoolQueryBuilder searchQueryFiler = new BoolQueryBuilder();

    if (team != null && DataInsightChartRepository.SUPPORTS_TEAM_FILTER.contains(dataInsightChartName)) {
      List<String> teamArray = Arrays.asList(team.split("\\s*,\\s*"));

      BoolQueryBuilder teamQueryFilter = QueryBuilders.boolQuery();
      teamQueryFilter.should(QueryBuilders.termsQuery(DataInsightChartRepository.DATA_TEAM, teamArray));
      searchQueryFiler.must(teamQueryFilter);
    }

    if (tier != null && DataInsightChartRepository.SUPPORTS_TIER_FILTER.contains(dataInsightChartName)) {
      List<String> tierArray = Arrays.asList(tier.split("\\s*,\\s*"));

      BoolQueryBuilder tierQueryFilter = QueryBuilders.boolQuery();
      tierQueryFilter.should(QueryBuilders.termsQuery(DataInsightChartRepository.DATA_ENTITY_TIER, tierArray));
      searchQueryFiler.must(tierQueryFilter);
    }

    RangeQueryBuilder dateQueryFilter =
        QueryBuilders.rangeQuery(DataInsightChartRepository.TIMESTAMP).gte(startTs).lte(endTs);

    searchQueryFiler.must(dateQueryFilter);
    return searchSourceBuilder.query(searchQueryFiler).fetchSource(false);
  }

  private static AggregationBuilder buildQueryAggregation(
      DataInsightChartResult.DataInsightChartType dataInsightChartName) throws IllegalArgumentException {
    DateHistogramAggregationBuilder dateHistogramAggregationBuilder =
        AggregationBuilders.dateHistogram(DataInsightChartRepository.TIMESTAMP)
            .field(DataInsightChartRepository.TIMESTAMP)
            .calendarInterval(DateHistogramInterval.DAY);

    TermsAggregationBuilder termsAggregationBuilder;
    SumAggregationBuilder sumAggregationBuilder;
    SumAggregationBuilder sumEntityCountAggregationBuilder =
        AggregationBuilders.sum(DataInsightChartRepository.ENTITY_COUNT)
            .field(DataInsightChartRepository.DATA_ENTITY_COUNT);

    switch (dataInsightChartName) {
      case PERCENTAGE_OF_ENTITIES_WITH_DESCRIPTION_BY_TYPE:
        termsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.ENTITY_TYPE)
                .field(DataInsightChartRepository.DATA_ENTITY_TYPE)
                .size(1000);
        sumAggregationBuilder =
            AggregationBuilders.sum(DataInsightChartRepository.COMPLETED_DESCRIPTION_FRACTION)
                .field(DataInsightChartRepository.DATA_COMPLETED_DESCRIPTIONS);
        return dateHistogramAggregationBuilder.subAggregation(
            termsAggregationBuilder
                .subAggregation(sumAggregationBuilder)
                .subAggregation(sumEntityCountAggregationBuilder));
      case PERCENTAGE_OF_ENTITIES_WITH_OWNER_BY_TYPE:
        termsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.ENTITY_TYPE)
                .field(DataInsightChartRepository.DATA_ENTITY_TYPE)
                .size(1000);
        sumAggregationBuilder =
            AggregationBuilders.sum(DataInsightChartRepository.HAS_OWNER_FRACTION)
                .field(DataInsightChartRepository.DATA_HAS_OWNER);
        return dateHistogramAggregationBuilder.subAggregation(
            termsAggregationBuilder
                .subAggregation(sumAggregationBuilder)
                .subAggregation(sumEntityCountAggregationBuilder));
      case TOTAL_ENTITIES_BY_TIER:
        termsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.ENTITY_TIER)
                .field(DataInsightChartRepository.DATA_ENTITY_TIER)
                .missing("NoTier")
                .size(1000);
        return dateHistogramAggregationBuilder.subAggregation(
            termsAggregationBuilder.subAggregation(sumEntityCountAggregationBuilder));
      case TOTAL_ENTITIES_BY_TYPE:
        termsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.ENTITY_TYPE)
                .field(DataInsightChartRepository.DATA_ENTITY_TYPE)
                .size(1000);
        return dateHistogramAggregationBuilder.subAggregation(
            termsAggregationBuilder.subAggregation(sumEntityCountAggregationBuilder));
      case DAILY_ACTIVE_USERS:
        return dateHistogramAggregationBuilder;
      case PAGE_VIEWS_BY_ENTITIES:
        termsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.ENTITY_TYPE)
                .field(DataInsightChartRepository.DATA_ENTITY_TYPE)
                .size(1000);
        SumAggregationBuilder sumPageViewsByEntityTypes =
            AggregationBuilders.sum(DataInsightChartRepository.PAGE_VIEWS).field(DataInsightChartRepository.DATA_VIEWS);
        return dateHistogramAggregationBuilder.subAggregation(
            termsAggregationBuilder.subAggregation(sumPageViewsByEntityTypes));
      case MOST_VIEWED_ENTITIES:
        termsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.ENTITY_FQN)
                .field(DataInsightChartRepository.DATA_ENTITY_FQN)
                .size(10)
                .order(BucketOrder.aggregation(DataInsightChartRepository.PAGE_VIEWS, false));

        TermsAggregationBuilder ownerTermsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.OWNER).field(DataInsightChartRepository.DATA_OWNER);
        TermsAggregationBuilder entityTypeTermsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.ENTITY_TYPE)
                .field(DataInsightChartRepository.DATA_ENTITY_TYPE);
        TermsAggregationBuilder entityHrefAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.ENTITY_HREF)
                .field(DataInsightChartRepository.DATA_ENTITY_HREF);
        SumAggregationBuilder sumEntityPageViewsAggregationBuilder =
            AggregationBuilders.sum(DataInsightChartRepository.PAGE_VIEWS).field(DataInsightChartRepository.DATA_VIEWS);

        return termsAggregationBuilder
            .subAggregation(sumEntityPageViewsAggregationBuilder)
            .subAggregation(ownerTermsAggregationBuilder)
            .subAggregation(entityTypeTermsAggregationBuilder)
            .subAggregation(entityHrefAggregationBuilder);
      case MOST_ACTIVE_USERS:
        termsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.USER_NAME)
                .field(DataInsightChartRepository.DATA_USER_NAME)
                .size(10)
                .order(BucketOrder.aggregation(DataInsightChartRepository.SESSIONS, false));
        TermsAggregationBuilder teamTermsAggregationBuilder =
            AggregationBuilders.terms(DataInsightChartRepository.TEAM).field(DataInsightChartRepository.DATA_TEAM);
        SumAggregationBuilder sumSessionAggregationBuilder =
            AggregationBuilders.sum(DataInsightChartRepository.SESSIONS)
                .field(DataInsightChartRepository.DATA_SESSIONS);
        SumAggregationBuilder sumUserPageViewsAggregationBuilder =
            AggregationBuilders.sum(DataInsightChartRepository.PAGE_VIEWS)
                .field(DataInsightChartRepository.DATA_PAGE_VIEWS);
        MaxAggregationBuilder lastSessionAggregationBuilder =
            AggregationBuilders.max(DataInsightChartRepository.LAST_SESSION)
                .field(DataInsightChartRepository.DATA_LAST_SESSION);
        SumAggregationBuilder sumSessionDurationAggregationBuilder =
            AggregationBuilders.sum(DataInsightChartRepository.SESSION_DURATION)
                .field(DataInsightChartRepository.DATA_TOTAL_SESSION_DURATION);
        return termsAggregationBuilder
            .subAggregation(sumSessionAggregationBuilder)
            .subAggregation(sumUserPageViewsAggregationBuilder)
            .subAggregation(lastSessionAggregationBuilder)
            .subAggregation(sumSessionDurationAggregationBuilder)
            .subAggregation(teamTermsAggregationBuilder);
      default:
        throw new IllegalArgumentException(String.format("Invalid dataInsightChartType name %s", dataInsightChartName));
    }
  }
}