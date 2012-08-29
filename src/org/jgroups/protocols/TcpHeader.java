// $Id: TcpHeader.java,v 1.1 2008/09/03 04:24:40 commerce\wuti7102 Exp $

package org.jgroups.protocols;


import org.jgroups.Header;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;




public class TcpHeader extends Header {
    public String group_addr=null;

    public TcpHeader() {
    } // used for externalization

    public TcpHeader(String n) {
        group_addr=n;
    }

    public String toString() {
        return "[TCP:group_addr=" + group_addr + "]";
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(group_addr);
    }


    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        group_addr=(String)in.readObject();
    }
}
