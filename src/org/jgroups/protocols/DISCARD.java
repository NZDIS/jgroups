// $Id: DISCARD.java,v 1.1 2008/09/03 04:24:40 commerce\wuti7102 Exp $

package org.jgroups.protocols;

import java.util.Properties;
import java.util.Vector;
import org.jgroups.*;
import org.jgroups.stack.*;
import org.jgroups.log.Trace;



/**
 Discards up or down messages based on a percentage, e.g. setting property 'up' to 0.1 causes 10%
 of all up messages to be discarded. Setting 'down' or 'up' to 0 causes no loss, whereas 1 discards
 all messages (not very useful).
 */

public class DISCARD extends Protocol
{
   Vector members = new Vector();
   double up = 0.0;    // probability of dropping up   msgs
   double down = 0.0;  // probability of dropping down msgs
   boolean excludeItself = false;   //if true don't discard messages sent/received in this stack
   Address localAddress;
   int num_sent = 25;  // don't discard the first 5 messages. Temporary, +++remove later !


   /** All protocol names have to be unique ! */
   public String getName()
   {
      return "DISCARD";
   }


   public boolean setProperties(Properties props)
   {
      String str;

      str = props.getProperty("up");
      if (str != null)
      {
         up = new Double(str).doubleValue();
         props.remove("up");
      }

      str = props.getProperty("down");
      if (str != null)
      {
         down = new Double(str).doubleValue();
         props.remove("down");
      }

      str = props.getProperty("excludeitself");
      if (str != null)
      {
         excludeItself = new Boolean(str).booleanValue();
         props.remove("excludeitself");
      }


      if (props.size() > 0)
      {
         System.err.println("DISCARD.setProperties(): these properties are not recognized:");
         props.list(System.out);
         return false;
      }
      return true;
   }


   public void up(Event evt)
   {
      Message msg;
      double r;

      if (evt.getType() == Event.SET_LOCAL_ADDRESS)
         localAddress = (Address) evt.getArg();


      if (evt.getType() == Event.MSG)
      {
         msg = (Message) evt.getArg();
         if (up > 0)
         {
            /*if(num_sent > 0) {
                    num_sent--;
                    passUp(evt);
                    return;
                }*/


            r = Math.random();
            if (r < up)
            {
               if (excludeItself && msg.getSrc().equals(localAddress))
               {
                  if (Trace.trace) Trace.info("DISCARD.up()", "excluding itself");
               }
               else
               {
                  if (Trace.trace) Trace.info("DISCARD.up()", "dropping message");
                  return;
               }
            }
         }
      }


      passUp(evt);
   }


   public void down(Event evt)
   {
      Message msg;
      double r;

      if (evt.getType() == Event.MSG)
      {
         msg = (Message) evt.getArg();

         if (down > 0)
         {
            /*if(num_sent > 0) {
                    num_sent--;
                    passDown(evt);
                    return;
                }*/
            r = Math.random();
            if (r < down)
            {

               if (excludeItself && msg.getSrc().equals(localAddress))
               {
                  if (Trace.trace) Trace.info("DISCARD.down()", "excluding itself");
               }
               else
               {
                  if (Trace.trace) Trace.info("DISCARD.down()", "dropping message");
                  return;
               }
            }
         }

      }
      passDown(evt);
   }
}
