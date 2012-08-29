// $Id: FD_SOCK.java,v 1.1 2008/09/03 04:24:39 commerce\wuti7102 Exp $

package org.jgroups.protocols;

import org.jgroups.*;
import org.jgroups.log.Trace;
import org.jgroups.stack.IpAddress;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Promise;
import org.jgroups.util.TimeScheduler;
import org.jgroups.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;


/**
 * Failure detection protocol based on sockets. Failure detection is ring-based. Each member creates a
 * server socket and announces its address together with the server socket's address in a multicast. A
 * pinger thread will be started when the membership goes above 1 and will be stopped when it drops below
 * 2. The pinger thread connects to its neighbor on the right and waits until the socket is closed. When
 * the socket is closed by the monitored peer in an abnormal fashion (IOException), the neighbor will be
 * suspected.<p> The main feature of this protocol is that no ping messages need to be exchanged between
 * any 2 peers, and failure detection relies entirely on TCP sockets. The advantage is that no activity
 * will take place between 2 peers as long as they are alive (i.e. have their server sockets open). The
 * FD_SOCK protocol will work for groups where members are on different hosts, but its main usage is when
 * all group members are on the same host.<p> The costs involved are 2 additional threads: one that
 * monitors the client side of the socket connection (to monitor a peer) and another one that manages the
 * server socket. However, those threads will be idle as long as both peers are running.
 * @author Bela Ban May 29 2001
 */
public class FD_SOCK extends Protocol implements Runnable {
    long          get_cache_timeout=3000;            // msecs to wait for the socket cache from the coordinator
    long          get_cache_retry_timeout=500;       // msecs to wait until we retry getting the cache from coord
    long          suspect_msg_interval=5000;         // (BroadcastTask): mcast SUSPECT every 5000 msecs
    int           num_tries=3;                       // attempts coord is solicited for socket cache until we give up
    Vector        members=new Vector();              // list of group members (updated on VIEW_CHANGE)
    boolean       srv_sock_sent=false;               // has own socket been broadcast yet ?
    Vector        pingable_mbrs=new Vector();        // mbrs from which we select ping_dest. may be subset of 'members'
    Promise       get_cache_promise=new Promise();   // used for rendezvous on GET_CACHE and GET_CACHE_RSP
    boolean       got_cache_from_coord=false;        // was cache already fetched ?
    Address       local_addr=null;                   // our own address
    ServerSocket  srv_sock=null;                     // server socket to which another member connects to monitor me
    ServerSocketHandler srv_sock_handler=null;             // accepts new connections on srv_sock
    IpAddress     srv_sock_addr=null;                // pair of server_socket:port
    Address       ping_dest=null;                    // address of the member we monitor
    Socket        ping_sock=null;                    // socket to the member we monitor
    InputStream   ping_input=null;                   // input stream of the socket to the member we monitor
    Thread        pinger_thread=null;                // listens on ping_sock, suspects member if socket is closed
    Hashtable     cache=new Hashtable();             // keys=Addresses, vals=IpAddresses (socket:port)
    int           start_port=10000;                  // start port for server socket (uses first available port)
    Promise       ping_addr_promise=new Promise();   // to fetch the ping_addr for ping_dest
    Object        sock_mutex=new Object();           // for access to ping_sock, ping_input
    TimeScheduler timer=null;
    BroadcastTask bcast_task=new BroadcastTask();    // to transmit SUSPECT message (until view change)
    boolean       regular_sock_close=false;          // used by interruptPingerThread() when new ping_dest is computed


    public String getName() {
        return "FD_SOCK";
    }


    public boolean setProperties(Properties props) {
        String str;

        str=props.getProperty("get_cache_timeout");
        if(str != null) {
            get_cache_timeout=new Long(str).longValue();
            props.remove("get_cache_timeout");
        }

        str=props.getProperty("suspect_msg_interval");
        if(str != null) {
            suspect_msg_interval=new Long(str).longValue();
            props.remove("suspect_msg_interval");
        }

        str=props.getProperty("num_tries");
        if(str != null) {
            num_tries=new Integer(str).intValue();
            props.remove("num_tries");
        }

        str=props.getProperty("start_port");
        if(str != null) {
            start_port=new Integer(str).intValue();
            props.remove("start_port");
        }

        if(props.size() > 0) {
            System.err.println("FD_SOCK.setProperties(): the following properties are not recognized:");
            props.list(System.out);
            return false;
        }
        return true;
    }


    public void init() throws Exception {
        srv_sock_handler=new ServerSocketHandler();
        timer=stack != null ? stack.timer : null;
        if(timer == null)
            throw new Exception("FD_SOCK.init(): timer == null");
    }


    public void stop() {
        bcast_task.removeAll();
        stopPingerThread();
        stopServerSocket();
    }


    public void up(Event evt) {
        Message msg;
        FdHeader hdr=null;
        Object tmphdr;

        switch(evt.getType()) {

            case Event.SET_LOCAL_ADDRESS:
                local_addr=(Address) evt.getArg();
                break;

            case Event.MSG:
                msg=(Message) evt.getArg();
                tmphdr=msg.getHeader(getName());
                if(tmphdr == null || !(tmphdr instanceof FdHeader))
                    break;  // message did not originate from FD_SOCK layer, just pass up

                hdr=(FdHeader) msg.removeHeader(getName());

                switch(hdr.type) {

                    case FdHeader.SUSPECT:
                        if(hdr.mbrs != null) {
                            if(Trace.trace)
                                Trace.info("FD_SOCK.up()", "[SUSPECT] hdr=" + hdr);
                            for(int i=0; i < hdr.mbrs.size(); i++) {
                                passUp(new Event(Event.SUSPECT, hdr.mbrs.elementAt(i)));
                                passDown(new Event(Event.SUSPECT, hdr.mbrs.elementAt(i)));
                            }
                        }
                        else
                            Trace.warn("FD_SOCK.up()", "[SUSPECT]: hdr.mbrs == null");
                        break;

                        // If I have the sock for 'hdr.mbr', return it. Otherwise look it up in my cache and return it
                    case FdHeader.WHO_HAS_SOCK:
                        if(local_addr != null && local_addr.equals(msg.getSrc()))
                            return; // don't reply to WHO_HAS bcasts sent by me !

                        if(hdr.mbr == null) {
                            Trace.error("FD_SOCK.up(WHO_HAS_SOCK)", "hdr.mbr is null");
                            return;
                        }

                        if(Trace.trace)
                            Trace.info("FD_SOCK.up()", "who-has-sock " + hdr.mbr);

                        // 1. Try my own address, maybe it's me whose socket is wanted
                        if(local_addr != null && local_addr.equals(hdr.mbr) && srv_sock_addr != null) {
                            sendIHaveSockMessage(msg.getSrc(), local_addr, srv_sock_addr);  // unicast message to msg.getSrc()
                            return;
                        }

                        // 2. If I don't have it, maybe it is in the cache
                        if(cache.containsKey(hdr.mbr))
                            sendIHaveSockMessage(msg.getSrc(), hdr.mbr, (IpAddress) cache.get(hdr.mbr));  // ucast msg
                        break;


                        // Update the cache with the addr:sock_addr entry (if on the same host)
                    case FdHeader.I_HAVE_SOCK:
                        if(hdr.mbr == null || hdr.sock_addr == null) {
                            Trace.error("FD_SOCK.up()", "[I_HAVE_SOCK]: hdr.mbr is null or hdr.sock_addr == null");
                            return;
                        }


                        // if(!cache.containsKey(hdr.mbr))
                        cache.put(hdr.mbr, hdr.sock_addr); // update the cache
                        if(Trace.trace)
                            Trace.info("FD_SOCK.up()", "i-have-sock: " + hdr.mbr + " --> " +
                                                       hdr.sock_addr + " (cache is " + cache + ")");

                        if(ping_dest != null && hdr.mbr.equals(ping_dest))
                            ping_addr_promise.setResult(hdr.sock_addr);
                        break;

                        // Return the cache to the sender of this message
                    case FdHeader.GET_CACHE:
                        if(hdr.mbr == null) {
                            if(Trace.trace)
                                Trace.error("FD_SOCK.up()", "(GET_CACHE): hdr.mbr == null");
                            return;
                        }
                        hdr=new FdHeader(FdHeader.GET_CACHE_RSP);
                        hdr.cache=(Hashtable) cache.clone();
                        msg=new Message(hdr.mbr, null, null);
                        msg.putHeader(getName(), hdr);
                        passDown(new Event(Event.MSG, msg));
                        break;

                    case FdHeader.GET_CACHE_RSP:
                        if(hdr.cache == null) {
                            if(Trace.trace)
                                Trace.error("FD_SOCK.up()", "(GET_CACHE_RSP): cache is null");
                            return;
                        }
                        get_cache_promise.setResult(hdr.cache);
                        break;
                }
                return;
        }

        passUp(evt);                                        // pass up to the layer above us
    }


    public void down(Event evt) {
        Address mbr, tmp_ping_dest;
        View v;


        switch(evt.getType()) {

            case Event.UNSUSPECT:
                bcast_task.removeSuspectedMember((Address) evt.getArg());
                break;

            case Event.CONNECT:
                passDown(evt);
                srv_sock=Util.createServerSocket(start_port); // grab a random unused port above 10000
                srv_sock_addr=new IpAddress(srv_sock.getLocalPort());
                startServerSocket();
                if(pinger_thread == null)
                    startPingerThread();
                break;

            case Event.VIEW_CHANGE:
                synchronized(this) {
                    v=(View) evt.getArg();
                    members.removeAllElements();
                    members.addAll(v.getMembers());
                    bcast_task.adjustSuspectedMembers(members);
                    pingable_mbrs.removeAllElements();
                    pingable_mbrs.addAll(members);
                    passDown(evt);

                    if(Trace.trace) Trace.info("FD_SOCK.down()", "VIEW_CHANGE received: " + members);

                    // 1. Get the addr:pid cache from the coordinator (only if not already fetched)
                    if(!got_cache_from_coord) {
                        getCacheFromCoordinator();
                        got_cache_from_coord=true;
                    }


                    // 2. Broadcast my own addr:sock to all members so they can update their cache
                    if(!srv_sock_sent) {
                        if(srv_sock_addr != null) {
                            sendIHaveSockMessage(null, // send to all members
                                                 local_addr,
                                                 srv_sock_addr);
                            srv_sock_sent=true;
                        }
                        else
                            Trace.warn("FD_SOCK.down()", "(VIEW_CHANGE): srv_sock_addr == null");
                    }

                    // 3. Remove all entries in 'cache' which are not in the new membership
                    if(members != null) {
                        for(Enumeration e=cache.keys(); e.hasMoreElements();) {
                            mbr=(Address) e.nextElement();
                            if(!members.contains(mbr))
                                cache.remove(mbr);
                        }
                    }

                    if(members.size() > 1) {
                        if(pinger_thread != null && pinger_thread.isAlive()) {
                            tmp_ping_dest=determinePingDest();
                            if(ping_dest != null && tmp_ping_dest != null && !ping_dest.equals(tmp_ping_dest)) {
                                interruptPingerThread(); // allows the thread to use the new socket
                            }
                        }
                        else
                            startPingerThread(); // only starts if not yet running
                    }
                    else {
                        ping_dest=null;
                        stopPingerThread();
                    }
                }
                break;

            default:
                passDown(evt);
                break;
        }
    }


    /**
     * Runs as long as there are 2 members and more. Determines the member to be monitored and fetches its
     * server socket address (if n/a, sends a message to obtain it). The creates a client socket and listens on
     * it until the connection breaks. If it breaks, emits a SUSPECT message. It the connection is closed regularly,
     * nothing happens. In both cases, a new member to be monitored will be chosen and monitoring continues (unless
     * there are fewer than 2 members).
     */
    public void run() {
        Address tmp_ping_dest;
        IpAddress ping_addr;
        int max_fetch_tries=10;  // number of times a socket address is to be requested before giving up

        if(Trace.debug) Trace.info("FD_SOCK.run()", "pinger_thread started"); // +++ remove

        while(pinger_thread != null) {
            tmp_ping_dest=determinePingDest();
            if(Trace.trace)
                Trace.info("FD_SOCK.run()", "determinePingDest()=" + tmp_ping_dest +
                                            ", pingable_mbrs=" + pingable_mbrs);
            if(tmp_ping_dest == null) {
                ping_dest=null;
                pinger_thread=null;
                break;
            }
            ping_dest=tmp_ping_dest;
            ping_addr=fetchPingAddress(ping_dest);
            if(ping_addr == null) {
                Trace.error("FD_SOCK.run()", "socket address for " + ping_dest + " could not be fetched, retrying");
                if(--max_fetch_tries <= 0)
                    break;
                Util.sleep(2000);
                continue;
            }

            if(!setupPingSocket(ping_addr)) {
                Trace.info("FD_SOCK.run()", "could not create socket to " + ping_dest + "; suspecting " + ping_dest);
                broadcastSuspectMessage(ping_dest);
                pingable_mbrs.removeElement(ping_dest);
                continue;
            }

            if(Trace.trace)
                Trace.info("FD_SOCK.run()", "ping_dest=" + ping_dest + ", ping_sock=" + ping_sock + ", cache=" + cache);

            // at this point ping_input must be non-null, otherwise setupPingSocket() would have thrown an exception
            try {
                if(ping_input.read() == -1) // waits until the socket is closed
                    handleSocketClose(null);
            }
            catch(IOException ex) {  // we got here when the peer closed the socket --> suspect peer and then continue
                handleSocketClose(ex);
            }
            catch(Throwable catch_all_the_rest) {
                if(Trace.trace) Trace.info("FD_SOCK.run()", "exception=" + catch_all_the_rest);
            }
        }
        if(Trace.trace) Trace.info("FD_SOCK.run()", "pinger thread terminated");
        pinger_thread=null;
    }




    /* ----------------------------------- Private Methods -------------------------------------- */


    void handleSocketClose(Exception ex) {
        teardownPingSocket();     // make sure we have no leftovers
        if(!regular_sock_close) { // only suspect if socket was not closed regularly (by interruptPingerThread())
            if(Trace.trace)
                Trace.info("FD_SOCK.run()", "peer " + ping_dest +
                                            " closed socket (" + (ex != null ? ex.getClass().getName() : "eof") + ")");
            broadcastSuspectMessage(ping_dest);
            pingable_mbrs.removeElement(ping_dest);
        }
        else {
            if(Trace.trace) Trace.info("FD_SOCK.run()", "socket to " + ping_dest + " was reset");
            regular_sock_close=false;
        }
    }


    void startPingerThread() {
        if(pinger_thread == null) {
            pinger_thread=new Thread(this, "FD_SOCK Ping thread");
            pinger_thread.setDaemon(true);
            pinger_thread.start();
        }
    }


    void stopPingerThread() {
        if(pinger_thread != null && pinger_thread.isAlive()) {
            regular_sock_close=true;
            teardownPingSocket();
        }
        pinger_thread=null;
    }


    /**
     * Interrupts the pinger thread. The Thread.interrupt() method doesn't seem to work under Linux with JDK 1.3.1
     * (JDK 1.2.2 had no problems here), therefore we close the socket (setSoLinger has to be set !) if we are
     * running under Linux. This should be tested under Windows. (Solaris 8 and JDK 1.3.1 definitely works).<p>
     * Oct 29 2001 (bela): completely removed Thread.interrupt(), but used socket close on all OSs. This makes this
     * code portable and we don't have to check for OSs.
     * @see org.jgroups.tests.InterruptTest to determine whether Thread.interrupt() works for InputStream.read().
     */
    void interruptPingerThread() {
        if(pinger_thread != null && pinger_thread.isAlive()) {
            regular_sock_close=true;
            teardownPingSocket(); // will wake up the pinger thread. less elegant than Thread.interrupt(), but does the job
        }
    }

    void startServerSocket() {
        if(srv_sock_handler != null)
            srv_sock_handler.start(); // won't start if already running
    }

    void stopServerSocket() {
        if(srv_sock_handler != null)
            srv_sock_handler.stop();
    }


    /**
     * Creates a socket to <code>dest</code>, and assigns it to ping_sock. Also assigns ping_input
     */
    boolean setupPingSocket(IpAddress dest) {
        synchronized(sock_mutex) {
            if(dest == null) {
                Trace.error("FD_SOCK.setupPingSocket()", "destination address is null");
                return false;
            }
            try {
                ping_sock=new Socket(dest.getIpAddress(), dest.getPort());
                ping_sock.setSoLinger(true, 1);
                ping_input=ping_sock.getInputStream();
                return true;
            }
            catch(Throwable ex) {
                return false;
            }
        }
    }


    void teardownPingSocket() {
        synchronized(sock_mutex) {
            if(ping_sock != null) {
                try {
                    ping_sock.shutdownInput();
                    ping_sock.close();
                }
                catch(Exception ex) {
                }
                ping_sock=null;
            }
            if(ping_input != null) {
                try {
                    ping_input.close();
                }
                catch(Exception ex) {
                }
                ping_input=null;
            }
        }
    }


    /**
     * Determines coordinator C. If C is null and we are the first member, return. Else loop: send GET_CACHE message
     * to coordinator and wait for GET_CACHE_RSP response. Loop until valid response has been received.
     */
    void getCacheFromCoordinator() {
        Address coord;
        int attempts=num_tries;
        Message msg;
        FdHeader hdr;
        Hashtable result;

        get_cache_promise.reset();
        while(attempts > 0) {
            if((coord=determineCoordinator()) != null) {
                if(coord.equals(local_addr)) { // we are the first member --> empty cache
                    if(Trace.trace)
                        Trace.info("FD_SOCK.getCacheFromCoordinator()", "first member; cache is empty");
                    return;
                }
                hdr=new FdHeader(FdHeader.GET_CACHE);
                hdr.mbr=local_addr;
                msg=new Message(coord, null, null);
                msg.putHeader(getName(), hdr);
                passDown(new Event(Event.MSG, msg));
                result=(Hashtable) get_cache_promise.getResult(get_cache_timeout);
                if(result != null) {
                    cache.putAll(result); // replace all entries (there should be none !) in cache with the new values
                    if(Trace.trace)
                        Trace.info("FD_SOCK.getCacheFromCoordinator()", "got cache from " +
                                                                        coord + ": cache is " + cache);
                    return;
                }
                else {
                    if(Trace.trace)
                        Trace.error("FD_SOCK.getCacheFromCoordinator()", "received null cache; retrying");
                }
            }

            Util.sleep(get_cache_retry_timeout);
            --attempts;
        }
    }


    /**
     * Sends a SUSPECT message to all group members. Only the coordinator (or the next member in line if the coord
     * itself is suspected) will react to this message by installing a new view. To overcome the unreliability
     * of the SUSPECT message (it may be lost because we are not above any retransmission layer), the following scheme
     * is used: after sending the SUSPECT message, it is also added to the broadcast task, which will periodically
     * re-send the SUSPECT until a view is received in which the suspected process is not a member anymore. The reason is
     * that - at one point - either the coordinator or another participant taking over for a crashed coordinator, will
     * react to the SUSPECT message and issue a new view, at which point the broadcast task stops.
     */
    void broadcastSuspectMessage(Address suspected_mbr) {
        Message suspect_msg;
        FdHeader hdr;

        if(suspected_mbr == null) return;

        if(Trace.trace)
            Trace.info("FD_SOCK.broadcastSuspectMessage()", "suspecting " + suspected_mbr +
                                                            " (own address is " + local_addr + ")");

        // 1. Send a SUSPECT message right away; the broadcast task will take some time to send it (sleeps first)
        hdr=new FdHeader(FdHeader.SUSPECT);
        hdr.mbrs=new Vector();
        hdr.mbrs.addElement(suspected_mbr);
        suspect_msg=new Message();
        suspect_msg.putHeader(getName(), hdr);
        passDown(new Event(Event.MSG, suspect_msg));

        // 2. Add to broadcast task and start latter (if not yet running). The task will end when
        //    suspected members are removed from the membership
        bcast_task.addSuspectedMember(suspected_mbr);
    }


    void broadcastWhoHasSockMessage(Address mbr) {
        Message msg;
        FdHeader hdr;

        if(Trace.trace && local_addr != null && mbr != null)
            Trace.info("FD_SOCK.broadcastWhoHasSockMessage()", "[" + local_addr + "]: who-has " + mbr);

        msg=new Message();  // bcast msg
        hdr=new FdHeader(FdHeader.WHO_HAS_SOCK);
        hdr.mbr=mbr;
        msg.putHeader(getName(), hdr);
        passDown(new Event(Event.MSG, msg));
    }


    /**
     Sends or broadcasts a I_HAVE_SOCK response. If 'dst' is null, the reponse will be broadcast, otherwise
     it will be unicast back to the requester
     */
    void sendIHaveSockMessage(Address dst, Address mbr, IpAddress addr) {
        Message msg=new Message(dst, null, null);
        FdHeader hdr=new FdHeader(FdHeader.I_HAVE_SOCK);
        hdr.mbr=mbr;
        hdr.sock_addr=addr;
        msg.putHeader(getName(), hdr);

        if(Trace.debug) // +++ remove
            Trace.info("FD_SOCK.sendIHaveSockMessage()", "hdr=" + hdr);

        passDown(new Event(Event.MSG, msg));
    }


    /**
     Attempts to obtain the ping_addr first from the cache, then by unicasting q request to <code>mbr</code>,
     then by multicasting a request to all members.
     */
    IpAddress fetchPingAddress(Address mbr) {
        IpAddress ret=null;
        Message ping_addr_req;
        FdHeader hdr;

        if(mbr == null) {
            Trace.error("FD_SOCK.fetchPingAddress()", "mbr == null");
            return null;
        }
        // 1. Try to get from cache. Add a little delay so that joining mbrs can send their socket address before
        //    we ask them to do so
        ret=(IpAddress) cache.get(mbr);
        if(ret != null) {
            return ret;
        }

        Util.sleep(300);
        if((ret=(IpAddress) cache.get(mbr)) != null)
            return ret;


        // 2. Try to get from mbr
        ping_addr_promise.reset();
        ping_addr_req=new Message(mbr, null, null); // unicast
        hdr=new FdHeader(FdHeader.WHO_HAS_SOCK);
        hdr.mbr=mbr;
        ping_addr_req.putHeader(getName(), hdr);
        passDown(new Event(Event.MSG, ping_addr_req));
        ret=(IpAddress) ping_addr_promise.getResult(3000);
        if(ret != null) {
            return ret;
        }


        // 3. Try to get from all members
        ping_addr_req=new Message(null, null, null); // multicast
        hdr=new FdHeader(FdHeader.WHO_HAS_SOCK);
        hdr.mbr=mbr;
        ping_addr_req.putHeader(getName(), hdr);
        passDown(new Event(Event.MSG, ping_addr_req));
        ret=(IpAddress) ping_addr_promise.getResult(3000);
        return ret;
    }


    Address determinePingDest() {
        Address tmp;

        if(pingable_mbrs == null || pingable_mbrs.size() < 2 || local_addr == null)
            return null;
        for(int i=0; i < pingable_mbrs.size(); i++) {
            tmp=(Address) pingable_mbrs.elementAt(i);
            if(local_addr.equals(tmp)) {
                if(i + 1 >= pingable_mbrs.size())
                    return (Address) pingable_mbrs.elementAt(0);
                else
                    return (Address) pingable_mbrs.elementAt(i + 1);
            }
        }
        return null;
    }


    Address determineCoordinator() {
        return members.size() > 0 ? (Address) members.elementAt(0) : null;
    }





    /* ------------------------------- End of Private Methods ------------------------------------ */


    public static class FdHeader extends Header {
        static final int SUSPECT=10;
        static final int WHO_HAS_SOCK=11;
        static final int I_HAVE_SOCK=12;
        static final int GET_CACHE=13; // sent by joining member to coordinator
        static final int GET_CACHE_RSP=14; // sent by coordinator to joining member in response to GET_CACHE


        int       type=SUSPECT;
        Address   mbr=null;     // set on WHO_HAS_SOCK (requested mbr), I_HAVE_SOCK
        IpAddress sock_addr;    // set on I_HAVE_SOCK
        Hashtable cache=null;   // set on GET_CACHE_RSP
        Vector    mbrs=null;    // set on SUSPECT (list of suspected members)


        public FdHeader() {
        } // used for externalization

        public FdHeader(int type) {
            this.type=type;
        }


        public String toString() {
            StringBuffer sb=new StringBuffer();
            sb.append(type2String(type));
            if(mbr != null)
                sb.append(", mbr=" + mbr);
            if(sock_addr != null)
                sb.append(", sock_addr=" + sock_addr);
            if(cache != null)
                sb.append(", cache=" + cache);
            if(mbrs != null)
                sb.append(", mbrs=" + mbrs);
            return sb.toString();
        }


        public static String type2String(int type) {
            switch(type) {
                case SUSPECT:
                    return "SUSPECT";
                case WHO_HAS_SOCK:
                    return "WHO_HAS_SOCK";
                case I_HAVE_SOCK:
                    return "I_HAVE_SOCK";
                case GET_CACHE:
                    return "GET_CACHE";
                case GET_CACHE_RSP:
                    return "GET_CACHE_RSP";
                default:
                    return "unknown type (" + type + ")";
            }
        }

        public void writeExternal(ObjectOutput out) throws IOException {
            out.writeInt(type);
            out.writeObject(mbr);
            out.writeObject(sock_addr);
            out.writeObject(cache);
            out.writeObject(mbrs);
        }


        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            type=in.readInt();
            mbr=(Address) in.readObject();
            sock_addr=(IpAddress) in.readObject();
            cache=(Hashtable) in.readObject();
            mbrs=(Vector) in.readObject();
        }

    }


    /**
     * Handles the server-side of a client-server socket connection. Waits until a client connects, and then loops
     * until that client closes the connection. Note that there is no new thread spawned for the listening on the
     * client socket, therefore there can only be 1 client connection at the same time. Subsequent clients attempting
     * to create a connection will be blocked until the first client closes its connection. This should not be a problem
     * as the ring nature of the FD_SOCK protocol always has only 1 client connect to its right-hand-side neighbor.
     */
    private class ServerSocketHandler implements Runnable {
        Thread handler=null;
        Socket client_sock=null;
        InputStream in=null;


        ServerSocketHandler() {
            start();
        }

        void start() {
            if(handler == null) {
                handler=new Thread(this, "ServerSocketHandler thread");
                handler.setDaemon(true);
                handler.start();
            }
        }


        void stop() {
            if(handler != null && handler.isAlive()) {
                try {
                    srv_sock.close(); // this will terminate thread, peer will receive SocketException (socket close)
                }
                catch(Exception ex) {
                }
            }
            handler=null;
        }


        /** Only accepts 1 client connection at a time (saving threads) */
        public void run() {

            while(handler != null && srv_sock != null) {
                try {
                    if(Trace.debug) // +++ remove
                        Trace.info("FD_SOCK.ServerSocketHandler.run()", "waiting for client connections on port " +
                                                                        srv_sock.getLocalPort());
                    client_sock=srv_sock.accept();
                    if(Trace.debug) // +++ remove
                        Trace.info("FD_SOCK.ServerSocketHandler.run()", "accepted connection from " +
                                                                        client_sock.getInetAddress() + ":" + client_sock.getPort());
                    in=client_sock.getInputStream();
                    try {
                        while((in.read()) != -1) {
                        }
                    }
                    catch(IOException io_ex1) {
                    }
                    finally {
                        if(client_sock != null) {
                            try {
                                client_sock.close();
                            }
                            catch(Exception ex) {
                            }
                            client_sock=null;
                        }
                    }
                }
                catch(IOException io_ex2) {
                    break;
                }
            }
            handler=null;
        }

    }


    /**
     * Task that periodically broadcasts a list of suspected members to the group. Goal is not to lose
     * a SUSPECT message: since these are bcast unreliably, they might get dropped. The BroadcastTask makes
     * sure they are retransmitted until a view has been received which doesn't contain the suspected members
     * any longer. Then the task terminates.
     */
    private class BroadcastTask implements TimeScheduler.Task {
        Vector suspected_mbrs=new Vector();
        boolean stopped=false;


        /** Adds a suspected member. Starts the task if not yet running */
        public void addSuspectedMember(Address mbr) {
            if(mbr == null) return;
            if(!members.contains(mbr)) return;
            synchronized(suspected_mbrs) {
                if(!suspected_mbrs.contains(mbr)) {
                    suspected_mbrs.addElement(mbr);
                    if(Trace.trace)
                        Trace.info("FD_SOCK.BroadcastTask.addSuspectedMember()",
                                   "mbr=" + mbr + " (size=" + suspected_mbrs.size() + ")");
                }
                if(stopped && suspected_mbrs.size() > 0) {
                    stopped=false;
                    timer.add(this, true);
                }
            }
        }


        public void removeSuspectedMember(Address suspected_mbr) {
            if(suspected_mbr == null) return;
            if(Trace.trace) Trace.info("FD_SOCK.BroadcastTask.removeSuspectedMember()", "member is " + suspected_mbr);
            synchronized(suspected_mbrs) {
                suspected_mbrs.removeElement(suspected_mbr);
                if(suspected_mbrs.size() == 0)
                    stopped=true;
            }
        }


        public void removeAll() {
            synchronized(suspected_mbrs) {
                suspected_mbrs.removeAllElements();
                stopped=true;
            }
        }


        /**
         * Removes all elements from suspected_mbrs that are <em>not</em> in the new membership
         */
        public void adjustSuspectedMembers(Vector new_mbrship) {
            Address suspected_mbr;

            if(new_mbrship == null || new_mbrship.size() == 0) return;
            synchronized(suspected_mbrs) {
                for(Iterator it=suspected_mbrs.iterator(); it.hasNext();) {
                    suspected_mbr=(Address) it.next();
                    if(!new_mbrship.contains(suspected_mbr)) {
                        it.remove();
                        if(Trace.trace)
                            Trace.info("FD_SOCK.BroadcastTask.adjustSuspectedMembers()",
                                       "removed " + suspected_mbr + " (size=" + suspected_mbrs.size() + ")");
                    }
                }
                if(suspected_mbrs.size() == 0)
                    stopped=true;
            }
        }


        public boolean cancelled() {
            return stopped;
        }


        public long nextInterval() {
            return suspect_msg_interval;
        }


        public void run() {
            Message suspect_msg;
            FdHeader hdr;

            if(Trace.trace)
                Trace.info("FD_SOCK.BroadcastTask.run()", "broadcasting SUSPECT message [suspected_mbrs=" +
                                                          suspected_mbrs + "] to group");

            synchronized(suspected_mbrs) {
                if(suspected_mbrs.size() == 0) {
                    stopped=true;
                    if(Trace.trace) Trace.info("FD_SOCK.BroadcastTask.run()", "task done (no suspected members)");
                    return;
                }

                hdr=new FdHeader(FdHeader.SUSPECT);
                hdr.mbrs=(Vector) suspected_mbrs.clone();
            }
            suspect_msg=new Message();       // mcast SUSPECT to all members
            suspect_msg.putHeader(getName(), hdr);
            passDown(new Event(Event.MSG, suspect_msg));
            if(Trace.trace) Trace.info("FD_SOCK.BroadcastTask.run()", "task done");
        }
    }


}
