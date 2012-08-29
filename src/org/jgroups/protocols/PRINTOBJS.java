// $Id: PRINTOBJS.java,v 1.1 2008/09/03 04:24:40 commerce\wuti7102 Exp $

package org.jgroups.protocols;

import org.jgroups.*;
import org.jgroups.util.*;
import org.jgroups.stack.*;


public class PRINTOBJS extends Protocol {

    public PRINTOBJS() {
    }

    public String getName() {
        return "PRINTOBJS";
    }


    public void up(Event evt) {
        Object obj=null;
        byte[] buf;
        Message msg;

        if(evt.getType() != Event.MSG) {
            System.out.println("------------ PRINTOBJS (received event) ----------------");
            System.out.println(evt);
            System.out.println("--------------------------------------------------------");
            passUp(evt);
            return;
        }

        msg=(Message)evt.getArg();
        if(msg.getLength() > 0) {
            try {
                obj=msg.getObject();
            }
            catch(ClassCastException cast_ex) {
                System.out.println("------------ PRINTOBJS (received) ----------------------");
                System.out.println(msg);
                System.out.println("--------------------------------------------------------");
                passUp(evt);
                return;
            }
            catch(Exception e) {
                System.err.println(e);
            }

            System.out.println("------------ PRINTOBJS (received) ----------------------");
            System.out.println(obj);
            System.out.println("--------------------------------------------------------");
        }
        else
            System.out.println("------- PRINTOBJS (received null msg from " + msg.getSrc() + ", headers are " +
                    msg.printObjectHeaders() + ") --------");

        passUp(evt);
    }


    public void down(Event evt) {
        Object obj=null;
        byte[] buf;
        Message msg;

        if(evt.getType() != Event.MSG) {
            System.out.println("------------ PRINTOBJS (sent event) --------------------");
            System.out.println(evt);
            System.out.println("--------------------------------------------------------");
            passDown(evt);
            return;
        }

        msg=(Message)evt.getArg();
        if(msg.getLength() > 0) {
            try {
                obj=msg.getObject();
            }
            catch(ClassCastException cast_ex) {
                System.out.println("------------ PRINTOBJS (sent) --------------------------");
                System.out.println(msg);
                System.out.println("--------------------------------------------------------");
                passDown(evt);
                return;
            }
            catch(Exception e) {
                System.err.println(e);
            }

            System.out.println("------------ PRINTOBJS (sent) --------------------------");
            System.out.println(obj);
            System.out.println("--------------------------------------------------------");
        }
        else
            System.out.println("------- PRINTOBJS (sent null msg to " + msg.getDest() + ", headers are " +
                    msg.printObjectHeaders() + " ) -------------");

        passDown(evt);
    }


    public void reset() {
        System.out.println("PRINTOBJS protocol is reset");
    }

    public String toString() {
        return "Protocol PRINTOBJS";
    }


}
