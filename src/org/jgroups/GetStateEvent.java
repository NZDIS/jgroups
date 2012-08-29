// $Id: GetStateEvent.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups;

public class GetStateEvent {
    Object requestor=null;

    public GetStateEvent(Object requestor) {this.requestor=requestor;}

    public Object getRequestor() {return requestor;}

    public String toString() {return "GetStateEvent[requestor=" + requestor + "]";}
}
