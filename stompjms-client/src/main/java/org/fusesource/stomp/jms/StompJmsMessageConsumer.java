/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.stomp.jms;

import org.fusesource.hawtbuf.AsciiBuffer;
import org.fusesource.hawtdispatch.CustomDispatchSource;
import org.fusesource.hawtdispatch.Dispatch;
import org.fusesource.hawtdispatch.OrderedEventAggregator;
import org.fusesource.stomp.client.Promise;
import org.fusesource.stomp.jms.message.StompJmsMessage;

import javax.jms.IllegalStateException;
import javax.jms.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * implementation of a Jms Message Consumer
 */
public class StompJmsMessageConsumer implements MessageConsumer, StompJmsMessageListener {
    
    final StompJmsSession session;
    final StompJmsDestination destination;
    final AsciiBuffer id;
    final AtomicBoolean closed = new AtomicBoolean();
    boolean started;
    MessageListener messageListener;
    final String messageSelector;
    final MessageQueue messageQueue;
    final Lock lock = new ReentrantLock();
    final AtomicBoolean suspendedConnection = new AtomicBoolean();


    final CustomDispatchSource<AckCallbackFuture, AckCallbackFuture> ackSource;

    class AckCallbackFuture extends Promise<Void> {
        AsciiBuffer id;

        public AckCallbackFuture(AsciiBuffer id) {
            this.id = id;
        }

        public void success() {
            onSuccess(null);
        }

        public AsciiBuffer getId() {
            return id;
        }
    }

    protected StompJmsMessageConsumer(final AsciiBuffer id, StompJmsSession s, StompJmsDestination destination, String selector) {
        this.id = id;
        this.session = s;
        this.destination = destination;
        this.messageSelector = selector;
        this.messageQueue = new MessageQueue(session.consumerMessageBufferSize);

        if(  session.acknowledgementMode==Session.AUTO_ACKNOWLEDGE ) {
            // Then the STOMP client does not need to issue acks to the server, we suspend
            // TCP reads to avoid memory overruns.
            ackSource = null;
        } else {
            ackSource = Dispatch.createSource(new OrderedEventAggregator<AckCallbackFuture, AckCallbackFuture>() {
                public AckCallbackFuture mergeEvent(AckCallbackFuture previous, AckCallbackFuture events) {
                    return events;
                }
                public AckCallbackFuture mergeEvents(AckCallbackFuture previous, AckCallbackFuture events) {
                    return events;
                }
            }, session.channel.connection.getDispatchQueue());

            ackSource.setEventHandler(new Runnable() {
                public void run() {
                    AckCallbackFuture cb = ackSource.getData();
                    AsciiBuffer msgid = cb.getId();
                    try {
                        switch( session.acknowledgementMode ) {
                            case Session.CLIENT_ACKNOWLEDGE:
                                session.channel.ackMessage(id, msgid, session.currentTransactionId, true);
                                break;
                            case Session.DUPS_OK_ACKNOWLEDGE:
                            case Session.SESSION_TRANSACTED:
                                session.channel.ackMessage(id, msgid, session.currentTransactionId, false);
                            case Session.AUTO_ACKNOWLEDGE:
                                break;
                        }
                        cb.success();
                    } catch (JMSException e) {
                        cb.onFailure(e);
                        session.connection.onException(e);
                    }
                }
            });
            ackSource.resume();
        }
    }

    public void init() throws JMSException {
        session.add(this);
    }

    public boolean isDurableSubscription() {
        return false;
    }


    public boolean isBrowser() {
        return false;
    }

    /**
     * @throws JMSException
     * @see javax.jms.MessageConsumer#close()
     */
    public void close() throws JMSException {
        if(closed.compareAndSet(false, true)) {
            this.session.remove(this);
            if( suspendedConnection.compareAndSet(true, false) ) {
                session.channel.connection().resume();
            }
        }
    }


    public MessageListener getMessageListener() throws JMSException {
        checkClosed();
        return this.messageListener;
    }

    /**
     * @return the Message Selector
     * @throws JMSException
     * @see javax.jms.MessageConsumer#getMessageSelector()
     */
    public String getMessageSelector() throws JMSException {
        checkClosed();
        return this.messageSelector;
    }

    /**
     * @return a Message or null if closed during the operation
     * @throws JMSException
     * @see javax.jms.MessageConsumer#receive()
     */
    public Message receive() throws JMSException {
        checkClosed();
        try {
            return ack(this.messageQueue.dequeue(-1));
        } catch (Exception e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }

    /**
     * @param timeout
     * @return a MEssage or null
     * @throws JMSException
     * @see javax.jms.MessageConsumer#receive(long)
     */
    public Message receive(long timeout) throws JMSException {
        checkClosed();
        try {
            return ack(this.messageQueue.dequeue(timeout));
        } catch (InterruptedException e) {
            throw StompJmsExceptionSupport.create(e);
        }
    }

    /**
     * @return a Message or null
     * @throws JMSException
     * @see javax.jms.MessageConsumer#receiveNoWait()
     */
    public Message receiveNoWait() throws JMSException {
        checkClosed();
        Message result = ack(this.messageQueue.dequeueNoWait());
        return result;
    }

    /**
     * @param listener
     * @throws JMSException
     * @see javax.jms.MessageConsumer#setMessageListener(javax.jms.MessageListener)
     */
    public void setMessageListener(MessageListener listener) throws JMSException {
        checkClosed();
        this.messageListener = listener;
        drainMessageQueueToListener();

    }


    protected void checkClosed() throws IllegalStateException {
        if (this.closed.get()) {
            throw new IllegalStateException("The MessageProducer is closed");
        }
    }

    StompJmsMessage ack(final StompJmsMessage message) {
        if( message!=null ){
            if( session.acknowledgementMode != Session.CLIENT_ACKNOWLEDGE ) {
                doAck(message);
            }
        }
        return message;
    }

    private void doAck(final StompJmsMessage message) {
        if( ackSource==null ) {
            // We may need to resume the message flow.
            if( !this.messageQueue.isFull() ) {
                if( suspendedConnection.compareAndSet(true, false) ) {
                    session.channel.connection().resume();
                }
            }
        } else {
            final AckCallbackFuture future = new AckCallbackFuture(message.getMessageID());
            ackSource.getTargetQueue().execute(new Runnable() {
                public void run() {
                    ackSource.merge(future);
                }
            });
            try {
                future.await();
            } catch (Exception e) {
                throw new RuntimeException("Exception occurred sending ACK for message id : " + message.getMessageID(), e);
            }
        }
    }

    /**
     * @param message
     */
    public void onMessage(final StompJmsMessage message) {
        lock.lock();
        try {
            if( session.acknowledgementMode ==  Session.CLIENT_ACKNOWLEDGE ) {
                message.setAcknowledgeCallback(new Runnable(){
                    public void run() {
                        doAck(message);
                    }
                });
            }
//            System.out.println(""+session.channel.getSocket().getLocalAddress() +" recv "+ message.getMessageID());
            this.messageQueue.enqueue(message);
            // We may need to suspend the message flow.
            if( ackSource==null && this.messageQueue.isFull() ) {
                if(suspendedConnection.compareAndSet(false, true) ) {
                    session.channel.connection().suspend();
                }
            }
        } finally {
            lock.unlock();
        }
        if (this.messageListener != null && this.started) {
            session.getExecutor().execute(new Runnable() {
                public void run() {
                    StompJmsMessage message;
                    while( (message=messageQueue.dequeueNoWait()) !=null ) {
                        try {
                            messageListener.onMessage(message);
                            ack(message);
                        } catch (Exception e) {
                            session.connection.onException(e);
                        }
                    }
                }
            });
        }
    }

    /**
     * @return the id
     */
    public AsciiBuffer getId() {
        return this.id;
    }

    /**
     * @return the Destination
     */
    public StompJmsDestination getDestination() {
        return this.destination;
    }

    public void start() {
        lock.lock();
        try {
            this.started = true;
            this.messageQueue.start();
            drainMessageQueueToListener();
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        lock.lock();
        try {
            this.started = false;
            this.messageQueue.stop();
        } finally {
            lock.unlock();
        }
    }

    protected void rollback(AsciiBuffer transactionId) {
        this.messageQueue.rollback(transactionId);
    }

    private void drainMessageQueueToListener() {
        MessageListener listener = this.messageListener;
        if (listener != null) {
            if (!this.messageQueue.isEmpty()) {
                List<StompJmsMessage> drain = this.messageQueue.removeAll();
                for (StompJmsMessage m : drain) {
                    listener.onMessage(m);
                }
                drain.clear();
            }
        }
    }

    protected int getMessageQueueSize() {
        return this.messageQueue.size();
    }
}