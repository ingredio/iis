package eu.dnetlib.iis.wf.ptm.avro2rdb;

import static org.apache.spark.sql.functions.explode;
import static org.apache.spark.sql.functions.split;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.DataFrame;


/**
 * {@link DataFrame} transformation utils. 
 *  
 * @author mhorst
 *
 */
public class AvroToRdbTransformerUtils {
    
    protected static final String JOIN_TYPE_INNER = "inner";
    protected static final String JOIN_TYPE_LEFT_OUTER = "left_outer";
    protected static final String JOIN_TYPE_LEFTSEMI = "leftsemi";
    
    protected static final String FIELD_PUBID = "pubId";
    protected static final String FIELD_PROJECTID = "projectId";
    
    protected static final String FIELD_GRANTID = "grantId";
    protected static final String FIELD_ACRONYM = "acronym";
    protected static final String FIELD_FUNDER = "funder";
    protected static final String FIELD_FUNDING_LEVEL_0 = "fundingLevel0";
    protected static final String FIELD_FUNDING_LEVEL_1 = "fundingLevel1";
    protected static final String FIELD_FUNDING_LEVEL_2 = "fundingLevel2";
    protected static final String FIELD_CALLID = "callId";
    protected static final String FIELD_START_DATE = "startDate";
    protected static final String FIELD_END_DATE = "endDate";
    
    protected static final String FIELD_TITLE = "title";
    protected static final String FIELD_ABSTRACT = "abstract";
    protected static final String FIELD_FULLTEXT = "fulltext";
    protected static final String FIELD_PUBYEAR = "pubyear";
    protected static final String FIELD_DOI = "doi";
    
    protected static final String FIELD_KEYWORD = "keyword";
    
    protected static final String FIELD_CITATIONID = "citationId";
    protected static final String FIELD_REFERENCE = "reference";
    
    protected static final String FIELD_PMCID = "pmcId";
    protected static final String FIELD_PDBCODE = "pdbcode";
    
    
    /**
     * @param metadata input metadata to be filtered and transformed
     * @param text dataframe holding text to be joined with metadata
     * @param pubGrant publication - grant relation to be used for metadata filtering
     */
    protected static DataFrame filterMetadata(DataFrame metadata, DataFrame text, DataFrame pubProject) {
        DataFrame metadataSubset = metadata.select(
                metadata.col("id").as(FIELD_PUBID),
                metadata.col(FIELD_TITLE), 
                metadata.col(FIELD_ABSTRACT),
                metadata.col("language"),
                metadata.col("externalIdentifiers").getField("doi").as(FIELD_DOI),
                metadata.col("year"),
                metadata.col("keywords")
        );
        
        DataFrame textDeduped = text.dropDuplicates(new String[] {"id"});
        DataFrame metadataJoinedWithText = metadataSubset.join(textDeduped, 
                metadataSubset.col(FIELD_PUBID).equalTo(textDeduped.col("id")), JOIN_TYPE_LEFT_OUTER);
        
        // filtering by abstract OR text being not null and English language (or unspecified)
        // TODO introduce language recognition for unspecified lang
        Column languageColumn = metadataJoinedWithText.col("language");
        DataFrame metadataFiltered = metadataJoinedWithText.filter(
                metadataJoinedWithText.col(FIELD_ABSTRACT).isNotNull().or(metadataJoinedWithText.col("text").isNotNull()).and(
                        languageColumn.isNull().or(languageColumn.equalTo("eng"))));
        
        return metadataFiltered.join(pubProject, metadataFiltered.col(FIELD_PUBID).equalTo(pubProject.col(FIELD_PUBID)), JOIN_TYPE_LEFTSEMI);
    }
    
    /**
     * @param metadata dataframe with {@link #FIELD_PUBID} and keywords columns
     */
    protected static DataFrame buildPubKeyword(DataFrame metadata) {
     // no need to filter keywords by null, explode does the job
        return metadata.select(metadata.col(FIELD_PUBID), explode(metadata.col("keywords")).as(FIELD_KEYWORD));
    }
    
    /**
     * @param metadata dataframe with {@link #FIELD_PUBID} and text columns
     */
    protected static DataFrame buildPubFulltext(DataFrame metadata) {
        Column textColumn = metadata.col("text");
        return metadata.filter(textColumn.isNotNull()).select(metadata.col(FIELD_PUBID), textColumn.as(FIELD_FULLTEXT));
    }
    
    /**
     * @param citation dataframe with citations read from input avro datastore
     * @param publicationId dataframe with {@link #FIELD_PUBID} column, required for filtering citation datastore
     * @param confidenceLevelThreshold matched citation confidence level threshold
     */
    protected static DataFrame filterCitation(DataFrame citation, DataFrame publicationId,
            float confidenceLevelThreshold) {
        // TODO we should handle externally matched citations as well
        Column destinationDocumentIdColumn = citation.col("entry.destinationDocumentId");
        
        DataFrame citationInternal = citation
                .filter(destinationDocumentIdColumn.isNotNull().and(
                        citation.col("entry.confidenceLevel").$greater$eq(confidenceLevelThreshold)))
                .select(
                    citation.col("sourceDocumentId").as(FIELD_PUBID),
                    destinationDocumentIdColumn.as(FIELD_CITATIONID),
                    citation.col("entry.rawText").as(FIELD_REFERENCE));
        
        // filtering by publications subset
        return citationInternal.join(publicationId, 
                citationInternal.col(FIELD_PUBID).equalTo(publicationId.col(FIELD_PUBID)), JOIN_TYPE_LEFTSEMI);
    }
    
    /**
     * @param citation dataframe with {@link #FIELD_PUBID} and {@link #FIELD_CITATIONID} columns
     */
    protected static DataFrame buildPubCitation(DataFrame citation) {
        return citation.select(citation.col(FIELD_PUBID), citation.col(FIELD_CITATIONID)).dropDuplicates();
    }
    
    /**
     * @param project dataframe with projects read from input avro datastore
     * @param fundingClassWhitelist project funding class whitelist regex
     */
    protected static DataFrame filterProject(DataFrame project, String fundingClassWhitelist) {
        Column fundingClass = project.col("fundingClass");
        Column fundingClassRLike = fundingClass.rlike(fundingClassWhitelist);

        DataFrame filteredProject = project.filter(fundingClassRLike);

        return filteredProject.select(project.col("id").as(FIELD_PROJECTID),
                split(project.col("fundingClass"), "::").getItem(0).as(FIELD_FUNDER),
                project.col("fundingLevels").getItem(0).as(FIELD_FUNDING_LEVEL_0),
                project.col("fundingLevels").getItem(1).as(FIELD_FUNDING_LEVEL_1),
                project.col("fundingLevels").getItem(2).as(FIELD_FUNDING_LEVEL_2),
                project.col("projectGrantId").as(FIELD_GRANTID),
                project.col("projectAcronym").as(FIELD_ACRONYM),
                project.col("title").as(FIELD_TITLE),
                project.col("callId").as(FIELD_CALLID),
                project.col("startDate").as(FIELD_START_DATE),
                project.col("endDate").as(FIELD_END_DATE));
    }
    
    /**
     * @param documentToProject dataframe with document to project relations read from input avro datastore 
     * @param project dataframe with projects read from input avro datastore
     * @param confidenceLevelThreshold document to project relations confidence level threshold
     */
    protected static DataFrame filterPubProject(DataFrame documentToProject, DataFrame project, 
            float confidenceLevelThreshold) {
        DataFrame documentJoinedWithProjectDetails = documentToProject.join(project,
                documentToProject.col(FIELD_PROJECTID).equalTo(project.col(FIELD_PROJECTID)), JOIN_TYPE_INNER);

        Column confidenceLevel = documentJoinedWithProjectDetails.col("confidenceLevel");
        Column confidenceLevelGrEq = confidenceLevel.$greater$eq(confidenceLevelThreshold);

        DataFrame filtered = documentJoinedWithProjectDetails.filter(confidenceLevelGrEq);

        return filtered.select(documentToProject.col("documentId").as(FIELD_PUBID),
                documentToProject.col(FIELD_PROJECTID).as(FIELD_PROJECTID));
    }
    
    
    /**
     * @param documentToPdb dataframe with document to pdb relations read from input avro datastore
     * @param publicationId dataframe with {@link #FIELD_PUBID} column, required for filtering documentToPdb datastore 
     * @param confidenceLevelThreshold document to pdb relations confidence level threshold
     */
    protected static DataFrame buildPubPDBCodes(DataFrame documentToPdb, DataFrame publicationId, 
            float confidenceLevelThreshold) {
        // filtering by publications subset
        DataFrame filteredByPubId = documentToPdb.join(publicationId, 
                documentToPdb.col("documentId").equalTo(publicationId.col(FIELD_PUBID)), JOIN_TYPE_LEFTSEMI);
        
        return filteredByPubId
                .filter(filteredByPubId.col("confidenceLevel").$greater$eq(confidenceLevelThreshold))
                .select(filteredByPubId.col("documentId").as(FIELD_PMCID),
                        filteredByPubId.col("conceptId").as(FIELD_PDBCODE)
                ).distinct();
    }

}