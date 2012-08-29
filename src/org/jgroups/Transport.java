// $Id: Transport.java,v 1.1 2008/09/03 04:24:42 commerce\wuti7102 Exp $

package org.jgroups;

public interface Transport {    
    void     send(Message msg) throws Exception;
    Object   receive(long timeout) throws Exception;
}
