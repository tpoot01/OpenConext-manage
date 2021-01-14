package manage.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cloudyrock.mongock.ChangeLog;
import com.github.cloudyrock.mongock.ChangeSet;
import com.github.cloudyrock.mongock.driver.mongodb.springdata.v3.decorator.impl.MongockTemplate;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.DistinctIterable;
import lombok.SneakyThrows;
import manage.conf.MetaDataAutoConfiguration;
import manage.model.EntityType;
import manage.model.MetaData;
import manage.model.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@ChangeLog(order = "001")
@SuppressWarnings("unchecked")
public class MongoChangelog {

    public static final String REVISION_POSTFIX = "_revision";

    private static final Logger LOG = LoggerFactory.getLogger(MongoChangelog.class);

    @ChangeSet(order = "001", id = "createCollections", author = "okke.harsta@surf.nl")
    public void createCollections(MongockTemplate mongoTemplate) {
        this.doCreateSchemas(mongoTemplate, Arrays.asList("saml20_sp", "saml20_idp", "oidc10_rp"));
        if (!mongoTemplate.collectionExists("sequences")) {
            LOG.info("Creating sequence collection with new start seq {}", 999L);

            mongoTemplate.createCollection("sequences");
            mongoTemplate.save(new Sequence("sequence", 999L));
        }

    }

    @ChangeSet(order = "002", id = "addTextIndexes", author = "okke.harsta@surf.nl")
    public void addTextIndexes(MongockTemplate mongoTemplate) {
        doAddTestIndexes(mongoTemplate);
    }

    private void doAddTestIndexes(MongockTemplate mongoTemplate) {
        Stream.of(EntityType.values()).forEach(entityType -> {
            TextIndexDefinition textIndexDefinition = new TextIndexDefinition.TextIndexDefinitionBuilder()
                    .onField("$**")
                    .build();
            mongoTemplate.indexOps(entityType.getType()).ensureIndex(textIndexDefinition);
        });
    }

    @ChangeSet(order = "003", id = "addDefaultScopes", author = "okke.harsta@surf.nl")
    public void addDefaultScopes(MongockTemplate mongoTemplate) {
        if (!mongoTemplate.collectionExists("scopes")) {
            mongoTemplate.remove(new Query(), Scope.class);
            DistinctIterable<String> scopes = mongoTemplate.getCollection(EntityType.RP.getType())
                    .distinct("data.metaDataFields.scopes", String.class);
            List<Scope> allScopes = StreamSupport.stream(scopes.spliterator(), false)
                    .map(scope -> new Scope(scope, new HashMap<>()))
                    .collect(Collectors.toList());
            mongoTemplate.insert(allScopes, Scope.class);
        }
    }

    @ChangeSet(order = "004", id = "removeSessions", author = "okke.harsta@surf.nl", runAlways = true)
    public void removeSessions(MongockTemplate mongoTemplate) {
        mongoTemplate.remove(new Query(), "sessions");
    }

    @ChangeSet(order = "005", id = "revisionCreatedIndex", author = "okke.harsta@surf.nl")
    public void revisionCreatedIndex(MongockTemplate mongoTemplate) {
        Stream.of(EntityType.values()).forEach(entityType -> {
            mongoTemplate.indexOps(entityType.getType())
                    .ensureIndex(new Index("revision.created", Sort.Direction.DESC));
        });
    }

    @ChangeSet(order = "005", id = "revisionTerminatedIndex", author = "okke.harsta@surf.nl")
    public void revisionTerminatedIndex(MongockTemplate mongoTemplate) {
        Stream.of(EntityType.values()).forEach(entityType -> {
            mongoTemplate.indexOps(entityType.getType().concat(REVISION_POSTFIX))
                    .ensureIndex(new Index("revision.terminated", Sort.Direction.DESC));
        });
    }

    @ChangeSet(order = "006", id = "caseInsensitiveIndexEntityID", author = "okke.harsta@surf.nl")
    public void caseInsensitiveIndexEntityID(MongockTemplate mongoTemplate) {
        Stream.of(EntityType.values())
                .filter(entityType -> !entityType.equals(EntityType.STT))
                .map(EntityType::getType).forEach(val -> {
            IndexOperations indexOperations = mongoTemplate.indexOps(val);
            List<IndexInfo> indexInfo = indexOperations.getIndexInfo();
            if (indexInfo.stream().anyMatch(info -> info.getName().equals("data.entityid_1"))) {
                indexOperations.dropIndex("data.entityid_1");
            }
            indexOperations.ensureIndex(new Index("data.entityid", Sort.Direction.ASC).unique()
                    .collation(Collation.of("en").strength(2)));
        });
    }

    @SneakyThrows
    @ChangeSet(order = "007", id = "moveResourceServers", author = "okke.harsta@surf.nl", runAlways = true)
    public void moveResourceServers(MongockTemplate mongoTemplate) {
        this.doCreateSchemas(mongoTemplate, Collections.singletonList(EntityType.RS.getType()));

        Criteria criteria = Criteria.where("data.metaDataFields.isResourceServer").is(true);
        List<MetaData> resourceServers = mongoTemplate.findAllAndRemove(Query.query(criteria), MetaData.class, EntityType.RP.getType());
        MetaDataAutoConfiguration metaDataAutoConfiguration = new MetaDataAutoConfiguration(new ObjectMapper(),
                new ClassPathResource("/metadata_configuration"),
                new ClassPathResource("metadata_templates"));
        Map<String, Object> schemaRepresentation = metaDataAutoConfiguration.schemaRepresentation(EntityType.RS);
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schemaRepresentation.get("properties");
        Map<String, Object> metaDataFields = properties.get("metaDataFields");
        Map<String, Object> patternProperties = (Map<String, Object>) metaDataFields.get("patternProperties");

        List<Pattern> patterns = patternProperties.keySet().stream().map(key -> Pattern.compile(key)).collect(Collectors.toList());
        Map<String, Object> simpleProperties = (Map<String, Object>) metaDataFields.get("properties");

        resourceServers.forEach(rs -> migrateRelayingPartyToResourceServer(properties, patterns, simpleProperties, rs));

        BulkWriteResult bulkWriteResult = mongoTemplate.bulkOps(BulkOperations.BulkMode.ORDERED, MetaData.class, EntityType.RS.getType()).insert(resourceServers).execute();
        LOG.info(String.format("Migrated %s relying parties to resource server collection", bulkWriteResult.getInsertedCount()));

        List<String> identifiers = resourceServers.stream().map(metaData -> metaData.getId()).collect(Collectors.toList());
        Criteria revisionCriteria = Criteria.where("revision.parentId").in(identifiers);
        List<MetaData> revisions = mongoTemplate.findAllAndRemove(Query.query(revisionCriteria), MetaData.class, EntityType.RP.getType().concat(REVISION_POSTFIX));

        revisions.forEach(rev -> migrateRelayingPartyToResourceServer(properties, patterns, simpleProperties, rev));

        BulkWriteResult bulkWriteResultRevisions = mongoTemplate.bulkOps(BulkOperations.BulkMode.ORDERED, MetaData.class, EntityType.RS.getType().concat(REVISION_POSTFIX)).insert(revisions).execute();
        LOG.info(String.format("Migrated %s relying party revisions to resource server revisions collection", bulkWriteResultRevisions.getInsertedCount()));

    }

    private void migrateRelayingPartyToResourceServer(Map<String, Map<String, Object>> properties, List<Pattern> patterns, Map<String, Object> simpleProperties, MetaData rs) {
        rs.setType(EntityType.RS.getType());
        rs.getData().entrySet().removeIf(entry -> !properties.containsKey(entry.getKey()));
        rs.metaDataFields().entrySet().removeIf(entry -> !simpleProperties.containsKey(entry.getKey()) && patterns.stream().noneMatch(pattern -> pattern.matcher(entry.getKey()).matches()));
    }

    private void doCreateSchemas(MongockTemplate mongoTemplate, List<String> connectionTypes) {
        connectionTypes.forEach(schema -> {
            if (!mongoTemplate.collectionExists(schema)) {
                mongoTemplate.createCollection(schema);
                String revision = schema.concat(REVISION_POSTFIX);
                mongoTemplate.createCollection(revision);
                mongoTemplate.indexOps(revision).ensureIndex(new Index("revision.parentId", Sort.Direction.ASC));
            }
        });
        connectionTypes.forEach(collection -> {
            IndexOperations indexOps = mongoTemplate.indexOps(collection);
            indexOps.getIndexInfo().stream()
                    .filter(indexInfo -> indexInfo.getName().contains("data.eid"))
                    .forEach(indexInfo -> indexOps.dropIndex(indexInfo.getName()));
            indexOps.ensureIndex(new Index("data.eid", Sort.Direction.ASC).unique());
            if (indexOps.getIndexInfo().stream().anyMatch(indexInfo -> indexInfo.getName().equals("field_entityid"))) {
                indexOps.dropIndex("field_entityid");
            }
            indexOps.ensureIndex(new Index("data.entityid", Sort.Direction.ASC).unique());
            indexOps.ensureIndex(new Index("data.state", Sort.Direction.ASC));
            if (!collection.equals(EntityType.RS.getType())) {
                indexOps.ensureIndex(new Index("data.allowedall", Sort.Direction.ASC));
                indexOps.ensureIndex(new Index("data.allowedEntities.name", Sort.Direction.ASC));
                indexOps.ensureIndex(new Index("data.metaDataFields.coin:institution_id", Sort.Direction.ASC));
            }
        });
        connectionTypes.stream().map(s -> s + "_revision").forEach(collection -> {
            IndexOperations indexOps = mongoTemplate.indexOps(collection);
            indexOps.ensureIndex(new Index("revision.parentId", Sort.Direction.ASC));
        });
    }

}
