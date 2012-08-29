// $Id: RspCollector.java,v 1.1 2008/09/03 04:24:38 commerce\wuti7102 Exp $

package org.jgroups.blocks;

import org.jgroups.Message;
import org.jgroups.View;
import org.jgroups.Address;


public interface RspCollector {
    void receiveResponse(Message msg);
    void suspect(Address mbr);
    void viewChange(View new_view);
}
