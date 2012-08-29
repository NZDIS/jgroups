// $Id: TCPPING.java,v 1.1 2008/09/03 04:24:39 commerce\wuti7102 Exp $

package org.jgroups.protocols;


import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Message;
import org.jgroups.View;
import org.jgroups.log.Trace;
import org.jgroups.stack.IpAddress;
import org.jgroups.stack.Protocol;
import org.jgroups.util.List;

import java.net.InetAddress;
import java.util.Enumeration;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Vector;
import java.util.HashSet;
import java.util.Set;


/**
 * The TCPPING protocol layer retrieves the initial membership (used by the GMS when started
 * by sending event FIND_INITIAL_MBRS down the stack). We do this by mcasting TCPPING
 * requests to an IP MCAST address (or, if gossiping is enabled, by contacting the router).
 * The responses should allow us to determine the coordinator whom we have to contact,
 * e.g. in case we want to join the group.  When we are a server (after having received the
 * BECOME_SERVER event), we'll respond to TCPPING requests with a TCPPING response.<p> The
 * FIND_INITIAL_MBRS event will eventually be answered with a FIND_INITIAL_MBRS_OK event up
 * the stack.
 * @author Bela Ban
 */
public class TCPPING extends Protocol {
    Vector    members=new Vector(), initial_members=new Vector();
    Set members_set=new HashSet(); //copy of the members vector for fast random access    
    Address   local_addr=null;
    String    group_addr=null;
    String    groupname=null;
    long      timeout=3000;
    long      num_initial_members=2;
    int       port_range=1;        // number of ports to be probed for initial membership
    List      initial_hosts=null;  // hosts to be contacted for the initial membership
    boolean   is_server=false;


    public String getName() {
        return "TCPPING";
    }


    public Vector providedUpServices() {
        Vector ret=new Vector();
        ret.addElement(new Integer(Event.FIND_INITIAL_MBRS));
        return ret;
    }


    public boolean setProperties(Properties props) {
        String str;

        str=props.getProperty("timeout");              // max time to wait for initial members
        if(str != null) {
            timeout=new Long(str).longValue();
            props.remove("timeout");
        }

        str=props.getProperty("port_range");           // if member cannot be contacted on base port,
        if(str != null) {                              // how many times can we increment the port
            port_range=new Integer(str).intValue();
            if (port_range < 1) {
               port_range = 1;    
            }
            props.remove("port_range");
        }

        str=props.getProperty("num_initial_members");  // wait for at most n members
        if(str != null) {
            num_initial_members=new Integer(str).intValue();
            props.remove("num_initial_members");
        }

        str=props.getProperty("initial_hosts");
        if(str != null) {
            props.remove("initial_hosts");
            initial_hosts=createInitialHosts(str);
        }

        if(props.size() > 0) {
            System.err.println("TCPPING.setProperties(): the following properties are not recognized:");
            props.list(System.out);
            return false;
        }
        return true;
    }


    public void up(Event evt) {
        Message msg, rsp_msg;
        Object obj;
        PingHeader hdr, rsp_hdr;
        PingRsp rsp;
        Address coord;


        switch(evt.getType()) {

            case Event.MSG:
                msg=(Message) evt.getArg();
                obj=msg.getHeader(getName());
                if(obj == null || !(obj instanceof PingHeader)) {
                    passUp(evt);
                    return;
                }
                hdr=(PingHeader) msg.removeHeader(getName());

                switch(hdr.type) {

                    case PingHeader.GET_MBRS_REQ:   // return Rsp(local_addr, coord)
                        if(!is_server) {
                            //System.err.println("TCPPING.up(GET_MBRS_REQ): did not return a response " +
                            //	       "as I'm not a server yet !");
                            return;
                        }
                        synchronized(members) {
                            coord=members.size() > 0 ? (Address) members.firstElement() : local_addr;
                        }
                        rsp_msg=new Message(msg.getSrc(), null, null);
                        rsp_hdr=new PingHeader(PingHeader.GET_MBRS_RSP, new PingRsp(local_addr, coord));
                        rsp_msg.putHeader(getName(), rsp_hdr);
                        passDown(new Event(Event.MSG, rsp_msg));
                        return;

                    case PingHeader.GET_MBRS_RSP:   // add response to vector and notify waiting thread
                        rsp=(PingRsp) hdr.arg;
                        synchronized(initial_members) {
                            initial_members.addElement(rsp);
                            initial_members.notify();
                        }
                        return;

                    default:
                        Trace.warn("TCPPING.up()", "got TCPPING header with unknown type (" + hdr.type + ")");
                        return;
                }


            case Event.SET_LOCAL_ADDRESS:
                passUp(evt);
                local_addr=(Address)evt.getArg();
                // Add own address to initial_hosts if not present: we must always be able to ping ourself !
                if(initial_hosts != null && local_addr != null) {
                   List hlist;
                   boolean inInitialHosts = false;
                   for(Enumeration en=initial_hosts.elements(); en.hasMoreElements() && !inInitialHosts;) {
                      hlist=(List)en.nextElement();
                      if (hlist.contains(local_addr)) {
                         inInitialHosts = true;
                      }                   
                   }
                   if (!inInitialHosts) {
                      hlist = new List();
                      hlist.add(local_addr);
                      initial_hosts.add(hlist);
                      Trace.info("TCPPING.up()", "[SET_LOCAL_ADDRESS]: adding my own address (" + local_addr +
                              ") to initial_hosts; initial_hosts=" + initial_hosts);
                   }
                }
                break;

            default:
                passUp(evt);            // Pass up to the layer above us
                break;
        }
    }


    public void down(Event evt) {
        Message msg;
        PingHeader hdr;
        long time_to_wait, start_time;

        switch(evt.getType()) {

            case Event.FIND_INITIAL_MBRS:   // sent by GMS layer, pass up a GET_MBRS_OK event
                initial_members.removeAllElements();

                IpAddress h;
                List hlist;
                msg=new Message(null, null, null);
                msg.putHeader(getName(), new PingHeader(PingHeader.GET_MBRS_REQ, null));

                synchronized(members) {
                    int numMembers=members.size();
                    int numMemberInitialHosts = 0;
                    Address coord=numMembers > 0 ? (Address)members.firstElement() : local_addr;                                
                    for(Enumeration en=initial_hosts.elements(); en.hasMoreElements();) {
                        hlist=(List)en.nextElement();
                        boolean isMember = false;

                        for(Enumeration hen=hlist.elements(); hen.hasMoreElements() && !isMember && numMemberInitialHosts < numMembers;) {
                            h=(IpAddress)hen.nextElement();
                            if (members_set.contains(h)) {
                                //update the initial_members list for this already connected member
                                initial_members.add(new PingRsp(h, coord));
                                isMember = true;
                                numMemberInitialHosts++;
                                if(Trace.trace) {
                                    Trace.info("TCPPING.down()", "[FIND_INITIAL_MBRS] " + h + " is already a member");
                                }
                            } 
                        }
                        for(Enumeration hen=hlist.elements(); hen.hasMoreElements() && !isMember;) {
                            h=(IpAddress)hen.nextElement();
                            msg.setDest(h);
                            if(Trace.trace) {
                                Trace.info("TCPPING.down()", "[FIND_INITIAL_MBRS] sending PING request to " + msg.getDest());
                            }
                            passDown(new Event(Event.MSG, msg.copy()));
                        }
                    }
                }

                // 2. Wait 'timeout' ms or until 'num_initial_members' have been retrieved
                synchronized(initial_members) {
                    start_time=System.currentTimeMillis();
                    time_to_wait=timeout;

                    while(initial_members.size() < num_initial_members && time_to_wait > 0) {
                        try {
                            initial_members.wait(time_to_wait);
                        }
                        catch(Exception e) {
                        }
                        time_to_wait-=System.currentTimeMillis() - start_time;
                    }
                }

                if(Trace.trace) Trace.info("TCPPING.down()", "[FIND_INITIAL_MBRS] initial members are " + initial_members);

                // 3. Send response
                passUp(new Event(Event.FIND_INITIAL_MBRS_OK, initial_members));
                break;

            case Event.TMP_VIEW:
            case Event.VIEW_CHANGE:
                Vector tmp;
                if((tmp=((View)evt.getArg()).getMembers()) != null) {
                    synchronized(members) {
                        members.clear();
                        members.addAll(tmp);
                        members_set.clear();
                        members_set.addAll(tmp);
                    }
                }
                passDown(evt);
                break;

            case Event.BECOME_SERVER: // called after client has joined and is fully working group member
                passDown(evt);
                is_server=true;
                break;

            case Event.CONNECT:
                group_addr=(String) evt.getArg();
                passDown(evt);
                break;

            case Event.DISCONNECT:
                passDown(evt);
                break;

            default:
                passDown(evt);          // Pass on to the layer below us
                break;
        }
    }



    /* -------------------------- Private methods ---------------------------- */



    /**
     * Input is "daddy[8880],sindhu[8880],camille[5555]. Return List of IpAddresses
     */
    private List createInitialHosts(String l) {
        List tmp=new List();
        StringTokenizer tok=new StringTokenizer(l, ",");
        String t;

        while(tok.hasMoreTokens()) {
            try {
                t=tok.nextToken();
                String host=t.substring(0, t.indexOf('['));
                int port=new Integer(t.substring(t.indexOf('[') + 1, t.indexOf(']'))).intValue();
                List hosts = new List();
                for(int i=port; i < port + port_range; i++) {
                   hosts.add(new IpAddress(host, i));
                }
                tmp.add(hosts);
            }
            catch(NumberFormatException e) {
                Trace.error("TCPPING.createInitialHosts()", "exeption is " + e);
            }
        }

        return tmp;
    }

}

