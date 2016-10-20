package eu.dnetlib.iis.wf.metadataextraction;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.InvalidParameterException;

import org.jdom.Element;

import pl.edu.icm.cermine.ContentExtractor;

/**
 * Metadata extractor main class executing extraction 
 * for all files provided as arguments.
 * @author mhorst
 *
 */
public class MetadataExtractorMain {

	public static void main(String[] args) throws Exception {
		if (args.length>0) {
			for (String fileLoc : args) {
			    System.out.println("PROCESSING: " + fileLoc);
				ContentExtractor extractor = new ContentExtractor();
				extractor.getConf().setTimeDebug(true);
				InputStream inputStream = new FileInputStream(new File(fileLoc));
				try {
                    extractor.setPDF(inputStream);
					Element resultElem = extractor.getContentAsNLM();
//					XMLOutputter outputter = new XMLOutputter(Format.getPrettyFormat());
//					System.out.println(outputter.outputString(resultElem));
//					System.out.println();
				} finally {
					inputStream.close();
				}
			}
		} else {
			throw new InvalidParameterException("no pdf file path provided");
		}
	}

}
