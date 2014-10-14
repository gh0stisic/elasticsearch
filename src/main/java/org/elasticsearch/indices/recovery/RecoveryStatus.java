/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.indices.recovery;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.service.InternalIndexShard;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.store.StoreFileMetaData;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 */


public class RecoveryStatus implements AutoCloseable {

    private final ESLogger logger;

    private final static AtomicLong idGenerator = new AtomicLong();

    private final String RECOVERY_PREFIX = "recovery.";

    private final ShardId shardId;
    private final long recoveryId;
    private final InternalIndexShard indexShard;
    private final RecoveryState state;
    private final DiscoveryNode sourceNode;
    private final String tempFilePrefix;
    private final Store store;
    private final RecoveryTarget.RecoveryListener listener;

    private AtomicReference<Thread> waitingRecoveryThread = new AtomicReference<>();

    AtomicBoolean finished = new AtomicBoolean();

    // we start with 1 which will be decremented on cancel/close/failure
    final AtomicInteger refCount = new AtomicInteger(1);

    private volatile ConcurrentMap<String, IndexOutput> openIndexOutputs = ConcurrentCollections.newConcurrentMap();
    public final Store.LegacyChecksums legacyChecksums = new Store.LegacyChecksums();

    public RecoveryStatus(InternalIndexShard indexShard, DiscoveryNode sourceNode, RecoveryState state, RecoveryTarget.RecoveryListener listener) {
        this.recoveryId = idGenerator.incrementAndGet();
        this.listener = listener;
        this.logger = Loggers.getLogger(getClass(), indexShard.indexSettings(), indexShard.shardId());
        this.indexShard = indexShard;
        this.sourceNode = sourceNode;
        this.shardId = indexShard.shardId();
        this.state = state;
        this.state.getTimer().startTime(System.currentTimeMillis());
        this.tempFilePrefix = RECOVERY_PREFIX + this.state.getTimer().startTime() + ".";
        this.store = indexShard.store();
        // make sure the store is not released until we are done.
        store.incRef();
    }

    private final Set<String> tempFileNames = ConcurrentCollections.newConcurrentSet();

    public long recoveryId() {
        return recoveryId;
    }

    public ShardId shardId() {
        return shardId;
    }

    public InternalIndexShard indexShard() {
        return indexShard;
    }

    public DiscoveryNode sourceNode() {
        return this.sourceNode;
    }

    public RecoveryState state() {
        return state;
    }

    public Store store() {
        return store;
    }

    /** set a thread that should be interrupted if the recovery is canceled */
    public void setWaitingRecoveryThread(Thread thread) {
        waitingRecoveryThread.set(thread);
    }

    /**
     * clear the thread set by {@link #setWaitingRecoveryThread(Thread)}, making sure we
     * do not override another thread.
     */
    public void clearWaitingRecoveryThread(Thread threadToClear) {
        waitingRecoveryThread.compareAndSet(threadToClear, null);
    }

    public void stage(RecoveryState.Stage stage) {
        state.setStage(stage);
    }

    public RecoveryState.Stage stage() {
        return state.getStage();
    }

    /** renames all temporary files to their true name, potentially overriding existing files */
    public void renameAllTempFiles() throws IOException {
        Iterator<String> tempFileIterator = tempFileNames.iterator();
        final Directory directory = store.directory();
        while (tempFileIterator.hasNext()) {
            String tempFile = tempFileIterator.next();
            String origFile = originalNameForTempFile(tempFile);
            // first, go and delete the existing ones
            try {
                directory.deleteFile(origFile);
            } catch (FileNotFoundException e) {

            } catch (Throwable ex) {
                logger.debug("failed to delete file [{}]", ex, origFile);
            }
            // now, rename the files... and fail it it won't work
            store.renameFile(tempFile, origFile);
            // upon success, remove the temp file
            tempFileIterator.remove();
        }
    }

    /** cancel the recovery. calling this method will clean temporary files and release the store
     * unless this object is in use (in which case it will be cleaned once all ongoing users call
     * {@link #decRef()} or {@link #close()}
     *
     * if {@link #setWaitingRecoveryThread(Thread)} was used, the thread will be interrupted.
     */
    public void cancel(String reason) {
        if (finished.compareAndSet(false, true)) {
            logger.debug("recovery canceled (reason: [{}])", reason);
            // release the initial reference. recovery files will be cleaned as soon as ref count goes to zero, potentially now
            decRef();

            Thread thread = waitingRecoveryThread.get();
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    /**
     * fail the recovery and call listener
     *
     * @param e exception that encapsulating the failure
     * @param sendShardFailure indicates whether to notify the master of the shard failure
     **/
    public void fail(RecoveryFailedException e, boolean sendShardFailure) {
        if (finished.compareAndSet(false, true)) {
            try {
                listener.onRecoveryFailure(state, e, sendShardFailure);
            } catch (Exception notifyError) {
                logger.error("unexpected failure while notifying listener of failure", notifyError);
            }
            // release the initial reference. recovery files will be cleaned as soon as ref count goes to zero, potentially now
            decRef();
        }

    }

    /** mark the current recovery as done */
    public void markAsDone() {
        if (finished.compareAndSet(false, true)) {
            assert tempFileNames.isEmpty() : "not all temporary files are renamed";
            // release the initial reference. recovery files will be cleaned as soon as ref count goes to zero, potentially now
            decRef();
            listener.onRecoveryDone(state);
        }
    }


    public IndexOutput getOpenIndexOutput(String key) {
        return openIndexOutputs.get(key);
    }

    /**
     * returns a temporary file for the given file name. the temporary file is unique to this recovery and is later retrievable via
     * {@link #getTempFiles()} *
     */
    public String getTempNameForFile(String origFile) {
        String s = tempFilePrefix + origFile;
        tempFileNames.add(s);
        return s;
    }

    /** returns the temporary files created fro this recovery so far */
    public String[] getTempFiles() {
        return tempFileNames.toArray(new String[tempFileNames.size()]);
    }

    /** return true if the give file is a temporary file name issued by this recovery */
    public boolean isTempFile(String filename) {
        return tempFileNames.contains(filename);
    }

    /** returns the original file name for a temporary file name issued by this recovery */
    public String originalNameForTempFile(String tempFile) {
        assert isTempFile(tempFile);
        return tempFile.substring(tempFilePrefix.length());
    }

    public IndexOutput removeOpenIndexOutputs(String name) {
        final ConcurrentMap<String, IndexOutput> outputs = openIndexOutputs;
        if (outputs == null) {
            return null;
        }
        return outputs.remove(name);
    }

    public IndexOutput openAndPutIndexOutput(String key, String fileName, StoreFileMetaData metaData, Store store) throws IOException {
        String tempFileName = getTempNameForFile(fileName);
        // add first, before it's created
        tempFileNames.add(tempFileName);
        IndexOutput indexOutput = store.createVerifyingOutput(tempFileName, IOContext.DEFAULT, metaData);
        openIndexOutputs.put(key, indexOutput);
        return indexOutput;
    }

    /**
     * Used when holding a reference to this recovery status. Does *not* mean the recovery is done or finished.
     * For that use {@link #cancel(String)} or {@link #markAsDone()}
     *
     * @throws Exception
     */
    @Override
    public void close() throws Exception {
        decRef();
    }

    /**
     * Tries to increment the refCount of this RecoveryStatus instance. This method will return <tt>true</tt> iff the refCount was
     * incremented successfully otherwise <tt>false</tt>. Be sure to always call a corresponding {@link #decRef}, in a finally clause;
     *
     * @see #decRef()
     */
    public final boolean tryIncRef() {
        do {
            int i = refCount.get();
            if (i > 0) {
                if (refCount.compareAndSet(i, i + 1)) {
                    return true;
                }
            } else {
                return false;
            }
        } while (true);
    }

    /**
     * Decreases the refCount of this Store instance.If the refCount drops to 0, the recovery process this status represents
     * is seen as done and resources and temporary files are deleted.
     *
     * @see #tryIncRef
     */
    public final void decRef() {
        int i = refCount.decrementAndGet();
        assert i >= 0;
        if (i == 0) {
            closeInternal();
        }
    }

    private void closeInternal() {
        try {
            // clean open index outputs
            Iterator<Entry<String, IndexOutput>> iterator = openIndexOutputs.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, IndexOutput> entry = iterator.next();
                IOUtils.closeWhileHandlingException(entry.getValue());
                iterator.remove();
            }
            // trash temporary files
            for (String file : tempFileNames) {
                logger.trace("cleaning temporary file [{}]", file);
                store.deleteQuiet(file);
            }
        } finally {
            // free store. increment happens in constructor
            store.decRef();
        }
        legacyChecksums.clear();
    }

    @Override
    public String toString() {
        return shardId + " [" + recoveryId + "]";
    }
}
