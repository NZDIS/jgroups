package org.jgroups.protocols;

import org.jgroups.stack.Protocol;
import org.jgroups.*;

import java.util.Properties;
import java.util.Vector;
import java.io.ObjectOutput;
import java.io.IOException;
import java.io.ObjectInput;

/**
 * The coordinator attaches a small header to each (or every nth) message. If another coordinator <em>in the
 * same group</em> sees the message, it will initiate the merge protocol immediately by sending a MERGE
 * event up the stack.
 * @author Bela Ban, Aug 25 2003
 */
public class MERGEFAST extends Protocol {
    Address       local_addr=null;
    boolean       is_coord=false;
    final String  name="MERGEFAST";

    public String getName() {
        return name;
    }

    public boolean setProperties(Properties props) {
        return super.setProperties(props);
    }


    public void down(Event evt) {
        if(is_coord == true && evt.getType() == Event.MSG && local_addr != null) {
            Message msg=(Message)evt.getArg();
            Address dest=msg.getDest();
            if(dest == null || dest.isMulticastAddress()) {
                msg.putHeader(getName(), new MergefastHeader(local_addr));
            }
        }

        if(evt.getType() == Event.VIEW_CHANGE) {
            handleViewChange((View)evt.getArg());
        }

        passDown(evt);
    }



    public void up(Event evt) {
        switch(evt.getType()) {
            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address)evt.getArg();
                break;
            case Event.MSG:
                if(is_coord == false) // only handle message if we are coordinator
                    break;
                Message msg=(Message)evt.getArg();
                MergefastHeader hdr=(MergefastHeader)msg.removeHeader(name);
                passUp(evt);
                if(hdr != null && local_addr != null) {
                    Address other_coord=hdr.coord;
                    if(!local_addr.equals(other_coord)) {
                        sendUpMerge(new Address[]{local_addr, other_coord});
                    }
                }
                return; // event was already passed up
            case Event.VIEW_CHANGE:
                handleViewChange((View)evt.getArg());
                break;
        }
        passUp(evt);
    }


    void handleViewChange(View v) {
        Vector mbrs;
        if(local_addr == null)
            return;
        mbrs=v.getMembers();
        if(mbrs != null && mbrs.size() > 0 && local_addr.equals(mbrs.firstElement())) {
            is_coord=true;
        }
        else
            is_coord=false;
    }

    // todo: avoid sending up too many MERGE events
    void sendUpMerge(Address[] addresses) {
        Vector v=new Vector();
        for(int i=0; i < addresses.length; i++) {
            Address addr=addresses[i];
            v.add(addr);
        }
        passUp(new Event(Event.MERGE, v));
    }


    public static class MergefastHeader extends Header {
        Address coord=null;

        public MergefastHeader() {
        }

        public MergefastHeader(Address coord) {
            this.coord=coord;
        }

        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(coord);
        }

        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            coord=(Address)in.readObject();
        }

    }

}
