// $Id: Interval.java,v 1.1 2008/09/03 04:24:43 commerce\wuti7102 Exp $

package org.jgroups.stack;


/**
 * Manages retransmission timeouts. Always returns the next timeout, until the last timeout in the
 * array is reached. Returns the last timeout from then on, until reset() is called.
 * @author John Giorgiadis
 * @author Bela Ban
 */
public class Interval {
    private int    next=0;
    private long[] interval=null;

    public Interval(long[] interval) {
	if (interval.length == 0)
	    throw new IllegalArgumentException("Interval()");
	this.interval=interval;
    }

    public long first() { return interval[0]; }
    
    /** @return the next interval */
    public synchronized long next() {
	if (next >= interval.length)
	    return(interval[interval.length-1]);
	else
	    return(interval[next++]);
    }
    
    public long[] getInterval() { return interval; }

    public synchronized void reset() { next = 0; }
}

