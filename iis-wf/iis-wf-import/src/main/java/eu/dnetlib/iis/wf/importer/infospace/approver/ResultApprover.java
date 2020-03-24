package eu.dnetlib.iis.wf.importer.infospace.approver;

import java.io.Serializable;

import eu.dnetlib.dhp.schema.oaf.Oaf;

/**
 * Verifies whether provided object should be approved.
 * 
 * @author mhorst
 *
 */
public interface ResultApprover extends Serializable {

	
	/**
	 * Approves Oaf object. Returns true when approved, false otherwise.
	 * @param oaf {@link Oaf} object to be approved
	 */
	boolean approve(Oaf oaf);
	
}
