package com.gcplot.services.logs.disruptor;

import com.gcplot.logs.ParserContext;
import com.gcplot.logs.mapping.Mapper;
import com.gcplot.model.gc.GCEvent;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.EventHandlerGroup;
import com.lmax.disruptor.dsl.ProducerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:art.dm.ser@gmail.com">Artem Dmitriev</a>
 *         6/14/17
 */
public class PipeEventProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(PipeEventProcessor.class);
    private static final Object DUMMY = new Object();
    private final Consumer<List<GCEvent>> persister;
    private final Consumer<GCEvent> singlePersister;
    private final Mapper eventMapper;
    private Disruptor<GCEventBundle> disruptor;

    public PipeEventProcessor(Consumer<List<GCEvent>> persister, Consumer<GCEvent> singlePersister,
                              Mapper eventMapper) {
        this.persister = persister;
        this.singlePersister = singlePersister;
        this.eventMapper = eventMapper;
    }

    public void init() {
        disruptor = new Disruptor<>(GCEventBundle::new, 16 * 1024, new ThreadFactoryBuilder()
                .setDaemon(false).setNameFormat("ds-").build(), ProducerType.MULTI, new BlockingWaitStrategy());
        EventHandlerGroup group = null;
        if (eventMapper != Mapper.EMPTY) {
            final long mapperCount = Math.max(Runtime.getRuntime().availableProcessors() / 2, 1);
            EventHandler<GCEventBundle>[] mapperHandlers = new EventHandler[(int) mapperCount];
            for (int i = 0; i < mapperCount; i++) {
                final long s = i;
                mapperHandlers[i] = (e, sequence, endOfBatch) -> {
                    if (!e.isControl && sequence % mapperCount == s) {
                        try {
                            e.event = eventMapper.map(e.parserContext, e.rawEvent);
                            if (e.event == null) {
                                e.ignore();
                            }
                        } catch (Throwable t) {
                            e.ignore();
                            LOG.error(t.getMessage(), t);
                        }
                    }
                };
            }
            group = disruptor.handleEventsWith(mapperHandlers);
        }
        EventHandler<GCEventBundle> eventHandler = (e, sequence, endOfBatch) -> {
            try {
                if (!e.isControl && !e.isIgnore) {
                    e.event.analyseId(e.parserContext.analysisId()).jvmId(e.parserContext.jvmId());
                    if (e.parsingState.getFirstEvent() == null && !e.event.isOther()) {
                        e.parsingState.setFirstEvent(e.event);
                    }
                    if (e.event.isOther() || (e.parsingState.getLastPersistedEvent().get() != null &&
                            e.parsingState.getLastPersistedEvent().get().timestamp() >= e.event.timestamp())) {
                        e.ignore();
                    } else {
                        e.parsingState.setLastEvent(e.event);
                    }
                }
            } catch (Throwable t) {
                LOG.error(t.getMessage(), t);
                e.ignore();
            }
        };
        if (group != null) {
            group = group.then(eventHandler);
        } else {
            group = disruptor.handleEventsWith(eventHandler);
        }
        long persisterCount = Runtime.getRuntime().availableProcessors() * 2;
        EventHandler<GCEventBundle>[] persisterHandlers = new EventHandler[(int) persisterCount];
        for (int i = 0; i < persisterCount; i++) {
            final long s = i;
            persisterHandlers[i] = new EventHandler<GCEventBundle>() {
                @Override
                public void onEvent(GCEventBundle e, long sequence, boolean endOfBatch) throws Exception {
                    try {
                        ParsingState ps = e.parsingState;
                        List<GCEvent> batch = ps.getBatch().get();
                        if (!e.isControl && sequence % persisterCount == s) {
                            if (batch.size() > 0 && (batch.size() == ParsingState.MAX_BATCH_SIZE || endOfBatch)) {
                                persist(ps, batch);
                            }
                            if (!e.isIgnore) {
                                if (endOfBatch) {
                                    singlePersister.accept(e.event);
                                } else {
                                    batch.add(e.event);
                                    ps.getMonthsSum().set(ps.getMonthsSum().get() + e.event.occurred().getMonthOfYear());
                                }
                            }
                        } else if ((endOfBatch || e.isControl) && batch.size() > 0) {
                            persist(ps, batch);
                        }
                    } catch (Throwable t) {
                        LOG.error(t.getMessage(), t);
                    } finally {
                        if (e.isControl) {
                            e.future.complete(DUMMY);
                        }
                    }
                }

                private void persist(ParsingState ps, List<GCEvent> batch) {
                    if (ps.getMonthsSum().get() %
                            batch.get(0).occurred().getMonthOfYear() != 0) {
                        batch.forEach(singlePersister);
                    } else {
                        persister.accept(new ArrayList<>(batch));
                    }
                    batch.clear();
                    ps.getMonthsSum().set(0);
                }
            };
        }
        group.then(persisterHandlers);
        LOG.info("Starting Pipe Event Processor ...");
        disruptor.start();
        LOG.info("Pipe Event Processor started!");
    }

    public void shutdown() {
        LOG.info("Shutting down Pipe Event Processor ...");
        disruptor.shutdown();
        LOG.info("Pipe Event Processor stopped.");
    }

    public void processNext(Object rawEvent, ParserContext context, ParsingState commonState) {
        disruptor.publishEvent((event, sequence) ->
                event.reset().parsingState(commonState).rawEvent(rawEvent).parserContext(context));
    }

    public void finish(ParsingState commonState) {
        final CompletableFuture future = new CompletableFuture();
        disruptor.publishEvent((event, sequence) -> event.reset().future(future).control().parsingState(commonState));
        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            LOG.error(e.getMessage(), e);
        }
    }
}