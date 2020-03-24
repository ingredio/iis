package eu.dnetlib.iis.wf.importer.infospace.approver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eu.dnetlib.dhp.schema.oaf.Oaf;

/**
 * Complex approver encapsulating multiple approvers.
 * 
 * @author mhorst
 * 
 */
public class ComplexApprover implements ResultApprover {

    private static final long serialVersionUID = -2609668045944653844L;
        
    private final List<ResultApprover> approvers;

    // ------------------------ CONSTRUCTORS --------------------------
    
    public ComplexApprover() {
        this.approvers = new ArrayList<ResultApprover>();
    }

    public ComplexApprover(ResultApprover... approvers) {
        this.approvers = Arrays.asList(approvers);
    }

    // ------------------------ LOGIC --------------------------
    
    @Override
    public boolean approve(Oaf oaf) {
        for (ResultApprover approver : approvers) {
            if (!approver.approve(oaf)) {
                return false;
            }
        }
        return true;
    }

}
