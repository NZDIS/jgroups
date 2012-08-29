// $Id: SetStateEvent.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups;






/**
 * Encapsulates a state returned by <code>Channel.receive()</code>, as requested by
 * <code>Channel.getState(s)</code> previously. State could be a single state (as requested by
 * <code>Channel.getState</code>) or a vector of states (as requested by
 * <code>Channel.getStates</code>).
 * @author Bela Ban
 */
public class SetStateEvent {
    byte[]     state=null;         // state


    public SetStateEvent(byte[] state) {
	this.state=state;
    }

    public byte[] getArg() {return state;}

    public String toString() {return "SetStateEvent[state=" + state + "]";}

}
