// $Id: TimeoutException.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups;

import java.util.List;



public class TimeoutException extends Exception {
    List failed_mbrs=null; // members that failed responding

    public TimeoutException() {
        super("TimeoutExeption");
    }

    public TimeoutException(String msg) {
        super(msg);
    }

    public TimeoutException(List failed_mbrs) {
        super("TimeoutExeption");
        this.failed_mbrs=failed_mbrs;
    }


    public String toString() {
        StringBuffer sb=new StringBuffer();

        sb.append(super.toString());

        if(failed_mbrs != null && failed_mbrs.size() > 0)
            sb.append(" (failed members: ").append(failed_mbrs);
        return sb.toString();
    }
}
