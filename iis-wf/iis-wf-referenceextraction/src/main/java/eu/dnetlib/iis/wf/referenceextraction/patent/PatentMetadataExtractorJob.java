package eu.dnetlib.iis.wf.referenceextraction.patent;

import java.io.IOException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import eu.dnetlib.iis.common.java.io.HdfsUtils;
import eu.dnetlib.iis.common.spark.JavaSparkContextFactory;
import eu.dnetlib.iis.metadataextraction.schemas.DocumentText;
import eu.dnetlib.iis.referenceextraction.patent.schemas.ImportedPatent;
import eu.dnetlib.iis.referenceextraction.patent.schemas.Patent;
import eu.dnetlib.iis.wf.referenceextraction.patent.parser.OpsPatentMetadataXPathBasedParser;
import eu.dnetlib.iis.wf.referenceextraction.patent.parser.PatentMetadataParser;
import eu.dnetlib.iis.wf.referenceextraction.patent.parser.PatentMetadataParserException;
import pl.edu.icm.sparkutils.avro.SparkAvroLoader;
import pl.edu.icm.sparkutils.avro.SparkAvroSaver;
import scala.Tuple2;

/**
 * Job responsible for extracting {@link Patent} metadata out of the XML file
 * obtained from EPO endpoint.
 * 
 * @author mhorst
 *
 */
public class PatentMetadataExtractorJob {

    private static final SparkAvroLoader avroLoader = new SparkAvroLoader();
    private static final SparkAvroSaver avroSaver = new SparkAvroSaver();

    // ------------------------ LOGIC --------------------------

    public static void main(String[] args) throws IOException {
        JobParameters params = new JobParameters();
        JCommander jcommander = new JCommander(params);
        jcommander.parse(args);
        
        try (JavaSparkContext sc = JavaSparkContextFactory.withConfAndKryo(new SparkConf())) {
            HdfsUtils.remove(sc.hadoopConfiguration(), params.outputPath);
            
            PatentMetadataParser parser = new OpsPatentMetadataXPathBasedParser();
            
            JavaRDD<ImportedPatent> importedPatent = avroLoader.loadJavaRDD(sc, params.inputImportedPatentPath, ImportedPatent.class);
            JavaRDD<DocumentText> toBeProcessedContents = avroLoader.loadJavaRDD(sc, params.inputDocumentTextPath, DocumentText.class);
            
            JavaPairRDD<CharSequence, Tuple2<DocumentText, ImportedPatent>> pairedInput = toBeProcessedContents
                    .mapToPair(x -> new Tuple2<CharSequence, DocumentText>(x.getId(), x))
                    .join(importedPatent.mapToPair(x -> new Tuple2<CharSequence, ImportedPatent>(x.getApplnNr(), x)));
            
            JavaRDD<Patent> parsedPatents = pairedInput.map(x -> parse(x._2._1, x._2._2, parser));

            avroSaver.saveJavaRDD(parsedPatents, Patent.SCHEMA$, params.outputPath);
        }
    }

    // ------------------------ PRIVATE --------------------------

    private static Patent parse(DocumentText patent, ImportedPatent importedPatent, PatentMetadataParser parser) {
        try {
            return parser.parse(patent.getText(), fillDataFromImport(Patent.newBuilder(), importedPatent)).build();
        } catch (PatentMetadataParserException e) {
            throw new RuntimeException(e);
        }
    }

    private static Patent.Builder fillDataFromImport(Patent.Builder patentBuilder, ImportedPatent importedPatent) {
        patentBuilder.setApplnAuth(importedPatent.getApplnAuth());
        patentBuilder.setApplnNr(importedPatent.getApplnNr());
        return patentBuilder;
    }

    @Parameters(separators = "=")
    private static class JobParameters {
        @Parameter(names = "-inputImportedPatentPath", required = true)
        private String inputImportedPatentPath;
        
        @Parameter(names = "-inputDocumentTextPath", required = true)
        private String inputDocumentTextPath;
        
        @Parameter(names = "-outputPath", required = true)
        private String outputPath;
    }
}