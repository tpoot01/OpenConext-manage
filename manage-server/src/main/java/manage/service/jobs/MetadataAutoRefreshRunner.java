package manage.service.jobs;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.core.JsonProcessingException;
import manage.conf.Features;
import manage.conf.MetaDataAutoConfiguration;
import manage.model.*;
import manage.service.FeatureService;
import manage.service.ImporterService;
import manage.service.MetaDataService;
import org.everit.json.schema.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Component
public class MetadataAutoRefreshRunner implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(MetadataAutoRefreshRunner.class);

    private static final String LOG_ALREADY_RUNNING = "Metadata auto refresh is already running";

    private static final String LOG_UPDATE_START = "Start metadata auto refresh";

    private static final String LOG_UPDATE_FINISHED = "Metadata auto refresh finished";

    public static final String METADATA_ENTITYID_KEY = "entityid";

    public static final String METADATA_URL_KEY = "metadataurl";

    public static final String METADATA_FIELDS_KEY = "metaDataFields";

    public static final String AUTO_REFRESH_KEY = "autoRefresh";

    private static final String IMPORT_ERROR_KEY = "errors";

    public static final String PROPERTIES_KEY = "properties";

    public static final String FIELDS_KEY = "fields";

    private static final String REFRESH_UPDATE_USER = "Metadata reaper";

    public static final String AUTO_REFRESH_REVISION_NOTE = "Metadata updated by auto refresh";

    private static final Lock execLock = new ReentrantLock();

    private static boolean running = false;

    private final MetaDataService metaDataService;

    private final ImporterService importerService;

    private final MetaDataAutoConfiguration metaDataAutoConfiguration;

    private final FeatureService featureService;

    private final boolean cronJobResponsible;

    public MetadataAutoRefreshRunner(MetaDataService metaDataService,
                                     ImporterService importerService,
                                     MetaDataAutoConfiguration metaDataAutoConfiguration,
                                     FeatureService featureService,
                                     @Value("${cron.node-cron-job-responsible}") boolean cronJobResponsible) {

        this.metaDataService = metaDataService;
        this.importerService = importerService;
        this.metaDataAutoConfiguration = metaDataAutoConfiguration;
        this.featureService = featureService;
        this.cronJobResponsible = cronJobResponsible;
    }

    @Override
    @Scheduled(cron = "${metadata_import.auto_refresh.cronSchedule}")
    public void run() {
        if (this.cronJobResponsible && featureService.isFeatureEnabled(Features.AUTO_REFRESH)) {
            // Check whether a Thread is already running
            if (running || !execLock.tryLock()) {
                LOG.warn(LOG_ALREADY_RUNNING);
                return;
            }

            // Set the lock and execute the reaper
            running = true;
            // General exception to ensure that we do not create a deadlock for the scheduled runner
            try {
                execute();
            } catch (Error | Exception e) {
                LOG.error("Error during thread: {}, stacktrace: {}", e.getMessage(), e.getStackTrace());
            }

            // Release the lock
            running = false;
            execLock.unlock();
        }
    }

    private void execute() {
        LOG.info(LOG_UPDATE_START);

        // Service Provider updates
        LOG.info("Updating Service Providers");
        metaDataService.findAllByType(EntityType.SP.getType()).forEach(this::doUpdate);

        // Identity Provider updates
        LOG.info("Updating Identity Providers");
        metaDataService.findAllByType(EntityType.IDP.getType()).forEach(this::doUpdate);

        LOG.info(LOG_UPDATE_FINISHED);
    }

    private void doUpdate(MetaData metaData) {
        String entityId = metaData.getData().get(METADATA_ENTITYID_KEY).toString();

        if (!metaData.isMetadataRefreshEnabled()) {
            LOG.info("Auto refresh is disabled - skipping for {}: {}", metaData.getType(), entityId);
            return;
        }

        if (!metaData.getData().containsKey(METADATA_URL_KEY) || null == metaData.getData().get(METADATA_URL_KEY)) {
            LOG.info("No metadata URL found - skipping for {}: {}", metaData.getType(), entityId);
            return;
        }

        LOG.info("Running auto refresh for {}: {}", metaData.getType(), entityId);
        // Retrieve updated fields and removed fields, then update metadata
        List<String> allowedFields = getAllowedFields(metaData, entityId);
        if (null == allowedFields) {
            // Exit here as the previous method will log in case of unexpected behavior
            return;
        }

        // Get the new metadata using the metadata URL
        String metadataUrl = metaData.getData().get(METADATA_URL_KEY).toString();
        Map<String, Object> importXMLUrlMetaData = importerService.importXMLUrl(EntityType.fromType(metaData.getType()),
                new Import(metadataUrl, null));
        if (importXMLUrlMetaData.containsKey(IMPORT_ERROR_KEY)) {
            LOG.info("Failed to parse metadata from url for {} {} and url {} with error: {}",
                    metaData.getType(), entityId, metadataUrl, importXMLUrlMetaData.get(IMPORT_ERROR_KEY));
            return;
        }

        Map<String, Object> fieldsToUpdate = getUpdatedFields(importXMLUrlMetaData, allowedFields);
        List<String> fieldsToRemove = getRemovedFields(importXMLUrlMetaData, allowedFields);
        if ((null == fieldsToUpdate || fieldsToUpdate.isEmpty()) && (null == fieldsToRemove || fieldsToRemove.isEmpty())) {
            LOG.info("No fields in the retrieved metadata that contain enabled auto refresh fields - skipping");
            return;
        }

        metaData.getData().put(Revision.REVISION_KEY, AUTO_REFRESH_REVISION_NOTE);
        if (null != fieldsToUpdate && !fieldsToUpdate.isEmpty()) {
            fieldsToUpdate.forEach((key, value) -> metaData.metaDataFields().put(key, value));
        }
        if (null != fieldsToRemove && !fieldsToRemove.isEmpty()) {
            fieldsToRemove.forEach(key -> metaData.metaDataFields().remove(key));
        }

        try {
            metaDataService.doPut(metaData, REFRESH_UPDATE_USER, metaData.isExcludedFromPush());
        } catch (JsonProcessingException exception) {
            LOG.info("Failed to save changes for {} {}: {}", metaData.getType(), entityId, exception.getMessage());
        } catch (ValidationException exception) {
            if (exception.getMessage().contains("No data is changed")) {
                LOG.info("No changes for {}: {}", metaData.getType(), entityId);
            } else {
                LOG.info("Failed to save changes for {} {}: {}", metaData.getType(), entityId, exception.getMessage());
            }
        }
    }

    private List<String> getAllowedFields(MetaData metaData, String entityId) {
         // Determine allowed fields to update
        List<String> allowedFields;
        if (metaData.isMetadataRefreshAllowAllEnabled()) {
            Map<String, Object> map = metaDataAutoConfiguration.schemaRepresentation(EntityType.fromType(metaData.getType()));
            Map<String, Object> allFields = (Map<String, Object>) ((Map) ((Map) ((Map) ((Map) map.getOrDefault(PROPERTIES_KEY, new HashMap<>()))
                    .getOrDefault(AUTO_REFRESH_KEY, new HashMap<>()))
                    .getOrDefault(PROPERTIES_KEY, new HashMap<>()))
                    .getOrDefault(FIELDS_KEY, new HashMap<>()))
                    .getOrDefault(PROPERTIES_KEY, new HashMap<>());
            allowedFields = new ArrayList<>(allFields.keySet());
        } else {
            Map<String, Boolean> configuredFields = null != metaData.getAutoRefresh() ?
                    (Map<String, Boolean>) metaData.getAutoRefresh().get(FIELDS_KEY) : null;
            if (null == configuredFields || configuredFields.isEmpty()) {
                LOG.info("No fields configured for auto refresh and allow all is disabled - skipping for {}: {}",
                        metaData.getType(), entityId);
                return null;
            }

            allowedFields = configuredFields.entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .map(Map.Entry::getKey).collect(Collectors.toList());
        }

        return allowedFields;
    }

    private Map<String, Object> getUpdatedFields(Map<String, Object> newMetaData, List<String> allowedFields) {
        Map<String, Object> metadataFields = (Map<String, Object>) newMetaData.get(METADATA_FIELDS_KEY);
        return metadataFields.entrySet().stream()
                .filter(entry -> allowedFields.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<String> getRemovedFields(Map<String, Object> newMetaData, List<String> allowedFields) {
        Map<String, Object> metaDataFields = (Map<String, Object>) newMetaData.get(METADATA_FIELDS_KEY);
        return allowedFields.stream()
                .filter(fieldKey -> !metaDataFields.containsKey(fieldKey))
                .collect(Collectors.toList());
    }

}