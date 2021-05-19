/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.xnio.nio.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.ChannelPipe;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.channels.StreamSourceChannel;

/**
 * Abstract test class for {@link ChannelPipe} usage test.
 * 
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 *
 */
public abstract class AbstractNioChannelPipeTest<S extends StreamSourceChannel, T extends StreamSinkChannel> {

    private static final Logger log = Logger.getLogger("TEST");
    protected final List<Throwable> problems = new CopyOnWriteArrayList<Throwable>();
    protected AtomicBoolean leftChannelOK;
    protected AtomicBoolean rightChannelOK;

    /**
     * Create the pipe channel.
     * 
     * @param worker the worker
     * @return       the create pipe channel
     */
    protected abstract ChannelPipe<S, T> createPipeChannel(XnioWorker worker) throws IOException;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected void doConnectionTest(final Runnable body, final ChannelListener<? extends S> leftChannelHandler, final ChannelListener<? super T> rightChannelHandler) throws Exception {
        String s = null;
        String s2 = null;
        final Xnio xnio = Xnio.getInstance("nio", NioFullDuplexChannelPipeTestCase.class.getClassLoader());
        @SuppressWarnings("deprecation")
        final XnioWorker worker = xnio.createWorker(OptionMap.create(Options.WORKER_WRITE_THREADS, 2, Options.WORKER_READ_THREADS, 2));
        try {
            final ChannelPipe<? extends S, ? extends T> channelPipe = createPipeChannel(worker);
            try {
                final Thread invokeLeftChannelHandler = new Thread(new ChannelListenerInvoker(leftChannelHandler, channelPipe.getLeftSide()));
                final Thread invokeRightChannelHandler = new Thread(new ChannelListenerInvoker(rightChannelHandler, channelPipe.getRightSide()));
                invokeLeftChannelHandler.start();
                invokeRightChannelHandler.start();
                invokeLeftChannelHandler.join();
                invokeRightChannelHandler.join();
                body.run();
                channelPipe.getLeftSide().close();
                channelPipe.getRightSide().close();
            } catch (Exception e) {
                log.errorf(e, "Error running body");
                throw e;
            } catch (Error e) {
                log.errorf(e, "Error running body");
                throw e;
            }finally {
                IoUtils.safeClose(channelPipe.getLeftSide());
                IoUtils.safeClose(channelPipe.getRightSide());
            }
        } finally {
            worker.shutdown();
            worker.awaitTermination(1L, TimeUnit.MINUTES);
        }
    }

    @Before
    public void setupTest() {
        problems.clear();
        leftChannelOK = new AtomicBoolean(false);
        rightChannelOK = new AtomicBoolean(false);
    }

    @After
    public void checkProblems() {
        assertTrue(leftChannelOK.get());
        assertTrue(rightChannelOK.get());
        for (Throwable problem : problems) {
            log.error("Test exception", problem);
        }
        assertTrue(problems.isEmpty());
    }

    @Test
    public void pipeCreation() throws Exception {
        log.info("Test: pipeCreation");
        doConnectionTest(new Runnable() {public void run(){} }, null, ChannelListeners.closingChannelListener());
        leftChannelOK.set(true);
        rightChannelOK.set(true);
    }

    @Test
    public void leftChannelClose() throws Exception {
        log.info("Test: leftChannelClose");
        final CountDownLatch latch = new CountDownLatch(4);
        doConnectionTest(new LatchAwaiter(latch), new ChannelListener<S>() {
            public void handleEvent(final S channel) {
                log.info("In pipe creation, leftChannel setup");
                try {
                    channel.getCloseSetter().set(new ChannelListener<StreamSourceChannel>() {
                        public void handleEvent(final StreamSourceChannel channel) {
                            log.info("In left channel close");
                            latch.countDown();
                        }
                    });
                    channel.close();
                    leftChannelOK.set(true);
                    latch.countDown();
                } catch (Throwable t) {
                    log.error("In left channel", t);
                    latch.countDown();
                    throw new RuntimeException(t);
                }
            }
        }, new ChannelListener<T>() {
            public void handleEvent(final T channel) {
                log.info("In pipe creation, rightChannel setup");
                channel.getCloseSetter().set(new ChannelListener<StreamSinkChannel>() {
                    public void handleEvent(final StreamSinkChannel channel) {
                        log.info("In right channel close");
                        latch.countDown();
                    }
                });
                channel.getWriteSetter().set(new ChannelListener<StreamSinkChannel>() {
                    public void handleEvent(final StreamSinkChannel channel) {
                        log.info("In right channel readable");
                        try {
                            channel.write(ByteBuffer.allocate(100));
                        } catch (IOException e) {
                            rightChannelOK.set(true);
                            IoUtils.safeClose(channel);
                        }
                        latch.countDown();
                    }
                });
                channel.resumeWrites();
            }
        });
    }

    @Test
    public void rightChannelClose() throws Exception {
        log.info("Test: rightChannelClose");
        final CountDownLatch latch = new CountDownLatch(2);
        doConnectionTest(new LatchAwaiter(latch), new ChannelListener<S>() {
            public void handleEvent(final S channel) {
                try {
                    channel.getCloseSetter().set(new ChannelListener<StreamSourceChannel>() {
                        public void handleEvent(final StreamSourceChannel channel) {
                            latch.countDown();
                        }
                    });
                    channel.getReadSetter().set(new ChannelListener<StreamSourceChannel>() {
                        public void handleEvent(final StreamSourceChannel channel) {
                            try {
                                final int c = channel.read(ByteBuffer.allocate(100));
                                if (c == -1) {
                                    leftChannelOK.set(true);
                                    channel.close();
                                    return;
                                }
                                // retry
                                return;
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                    channel.resumeReads();
                } catch (Throwable t) {
                    try {
                        channel.close();
                    } catch (Throwable t2) {
                        log.errorf(t2, "Failed to close channel (propagating as RT exception)");
                        latch.countDown();
                        throw new RuntimeException(t);
                    }
                    throw new RuntimeException(t);
                }
            }
        }, new ChannelListener<T>() {
            public void handleEvent(final T channel) {
                try {
                    channel.getCloseSetter().set(new ChannelListener<StreamSinkChannel>() {
                        public void handleEvent(final StreamSinkChannel channel) {
                            rightChannelOK.set(true);
                            latch.countDown();
                        }
                    });
                    channel.close();
                } catch (Throwable t) {
                    log.errorf(t, "Failed to close channel (propagating as RT exception)");
                    latch.countDown();
                    throw new RuntimeException(t);
                }
            }
        });
    }

    @Test
    public void oneWayTransfer() throws Exception {
        log.info("Test: oneWayTransfer");
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicInteger leftChannelReceived = new AtomicInteger(0);
        final AtomicInteger rightChannelSent = new AtomicInteger(0);
        doConnectionTest(new LatchAwaiter(latch), new ChannelListener<S>() {
            public void handleEvent(final S channel) {
                channel.getCloseSetter().set(new ChannelListener<StreamSourceChannel>() {
                    public void handleEvent(final StreamSourceChannel channel) {
                        latch.countDown();
                    }
                });
                channel.getReadSetter().set(new ChannelListener<StreamSourceChannel>() {
                    public void handleEvent(final StreamSourceChannel channel) {
                        try {
                            int c;
                            while ((c = channel.read(ByteBuffer.allocate(100))) > 0) {
                                leftChannelReceived.addAndGet(c);
                            }
                            if (c == -1) {
                                IoUtils.safeClose(channel);
                            }
                        } catch (Throwable t) {
                            log.errorf(t, "Failed to close channel (propagating as RT exception)");
                            throw new RuntimeException(t);
                        }
                    }
                });
                final ByteBuffer buffer = ByteBuffer.allocate(100);
                try {
                    buffer.put("This Is A Test\r\n".getBytes("UTF-8")).flip();
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
                channel.resumeReads();
            }
        }, new ChannelListener<T>() {
            public void handleEvent(final T channel) {
                channel.getCloseSetter().set(new ChannelListener<StreamSinkChannel>() {
                    public void handleEvent(final StreamSinkChannel channel) {
                        latch.countDown();
                    }
                });
                final ByteBuffer buffer = ByteBuffer.allocate(100);
                try {
                    buffer.put("This Is A Test Gumma\r\n".getBytes("UTF-8")).flip();
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
                channel.getWriteSetter().set(new ChannelListener<StreamSinkChannel>() {
                    public void handleEvent(final StreamSinkChannel channel) {
                        try {
                            int c;
                            while ((c = channel.write(buffer)) > 0) {
                                if (rightChannelSent.addAndGet(c) > 1000) {
                                    final ChannelListener<StreamSinkChannel> listener = new ChannelListener<StreamSinkChannel>() {
                                        public void handleEvent(final StreamSinkChannel channel) {
                                            try {
                                                IoUtils.safeClose(channel);
                                            } catch (Throwable t) {
                                                log.errorf(t, "Failed to close channel (propagating as RT exception)");
                                                throw new RuntimeException(t);
                                            }
                                        }
                                    };
                                    channel.getWriteSetter().set(listener);
                                    listener.handleEvent(channel);
                                    return;
                                }
                                buffer.rewind();
                            }
                        } catch (Throwable t) {
                            log.errorf(t, "Failed to close channel (propagating as RT exception)");
                            throw new RuntimeException(t);
                        }
                    }
                });
                channel.resumeWrites();
            }
        });
        assertEquals(rightChannelSent.get(), leftChannelReceived.get());
        leftChannelOK.set(true);
        rightChannelOK.set(true);
    }

    private static class ChannelListenerInvoker<T extends Channel> implements Runnable {
        private final ChannelListener<T> channelListener;
        private final T channel;

        public ChannelListenerInvoker(ChannelListener<T> l, T c) {
            channelListener = l;
            channel = c;
        }

        public void run() {
            ChannelListeners.invokeChannelListener(channel, channelListener);
        }
    }

    protected static class LatchAwaiter implements Runnable {
        private final CountDownLatch latch;

        public LatchAwaiter(CountDownLatch l) {
            latch = l;
        }
        
        public void run() {
            try {
                assertTrue(latch.await(500L, TimeUnit.DAYS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
