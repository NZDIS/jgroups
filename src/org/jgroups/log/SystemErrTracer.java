// $Id: SystemErrTracer.java,v 1.1 2008/09/03 04:24:41 commerce\wuti7102 Exp $

package org.jgroups.log;


/**
 * Provides output to <code>System.err</code>. All methods defined
 * here have permissions that are either protected or package.
 */
public class SystemErrTracer extends SystemTracer {

    SystemErrTracer(int level) {
	super(level);
    }
    
    protected void doPrint(String message) {
	System.err.print(message);
    }
    
    protected void doFlush() {
	System.err.flush();
    }

}
