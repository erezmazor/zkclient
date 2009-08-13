package org.I0Itec.zkclient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;

import org.I0Itec.zkclient.ZkEventThread.ZkEvent;
import org.I0Itec.zkclient.exception.ZkException;
import org.I0Itec.zkclient.exception.ZkMarshallingError;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkTimeoutException;
import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper.States;

/**
 * Abstracts the interaction with zookeeper and allows permanent (not just one time) watches on nodes in ZooKeeper
 */
public class ZkClient implements Watcher {

    private final static Logger LOG = Logger.getLogger(ZkClient.class);

    private final IZkConnection _connection;
    private final Map<String, Set<IZkChildListener>> _childListener = new ConcurrentHashMap<String, Set<IZkChildListener>>();
    private final ConcurrentHashMap<String, Set<IZkDataListener>> _dataListener = new ConcurrentHashMap<String, Set<IZkDataListener>>();
    private final Set<IZkStateListener> _stateListener = new CopyOnWriteArraySet<IZkStateListener>();
    private KeeperState _currentState;
    private final ZkLock _zkEventLock = new ZkLock();
    private boolean _shutdownTriggered;
    private ZkEventThread _eventThread;
    // TODO PVo remove this later
    private Thread _zookeeperEventThread;

    public ZkClient(IZkConnection connection) {
        this(connection, Integer.MAX_VALUE);
    }

    public ZkClient(IZkConnection connection, int connectionTimeout) {
        _connection = connection;
        connect(connectionTimeout, this);
    }

    public ZkClient(String zkServers, int sessionTimeout, int connectionTimeout) {
        this(new ZkConnection(zkServers, sessionTimeout), connectionTimeout);
    }

    public ZkClient(String zkServers, int connectionTimeout) {
        this(new ZkConnection(zkServers), connectionTimeout);
    }

    public ZkClient(String serverstring) {
        this(serverstring, Integer.MAX_VALUE);
    }

    public void subscribeChildChanges(final String path, final IZkChildListener listener) throws InterruptedException {
        synchronized (_childListener) {
            Set<IZkChildListener> listeners = _childListener.get(path);
            if (listeners == null) {
                listeners = new CopyOnWriteArraySet<IZkChildListener>();
                _childListener.put(path, listeners);
            }
            listeners.add(listener);
        }
        watchForChilds(path);
    }

    public void unsubscribeChildChanges(String path, IZkChildListener childListener) {
        synchronized (_childListener) {
            final Set<IZkChildListener> listeners = _childListener.get(path);
            if (listeners != null) {
                listeners.remove(childListener);
            }
        }
    }

    public void subscribeDataChanges(String path, IZkDataListener listener) throws InterruptedException {
        Set<IZkDataListener> listeners;
        synchronized (_dataListener) {
            listeners = _dataListener.get(path);
            if (listeners == null) {
                listeners = new CopyOnWriteArraySet<IZkDataListener>();
                _dataListener.put(path, listeners);
            }
            listeners.add(listener);
        }
        watchForData(path);
        LOG.info("Subscribed data changes for " + path);
    }

    public void unsubscribeDataChanges(String path, IZkDataListener dataListener) {
        synchronized (_dataListener) {
            final Set<IZkDataListener> listeners = _dataListener.get(path);
            if (listeners != null) {
                listeners.remove(dataListener);
            }
        }
    }

    public void subscribeStateChanges(final IZkStateListener listener) {
        synchronized (_stateListener) {
            _stateListener.add(listener);
        }
    }

    public void unsubscribeStateChanges(IZkStateListener stateListener) {
        synchronized (_stateListener) {
            _stateListener.remove(stateListener);
        }
    }

    public void unsubscribeAll() {
        synchronized (_childListener) {
            _childListener.clear();
        }
        synchronized (_dataListener) {
            _dataListener.clear();
        }
        synchronized (_stateListener) {
            _stateListener.clear();
        }
    }

    // </listeners>

    public void createPersistent(String path) throws InterruptedException {
        create(path, null, CreateMode.PERSISTENT);
    }

    public void createPersistent(String path, Serializable serializable) throws InterruptedException {
        create(path, serializable, CreateMode.PERSISTENT);
    }

    public void createPersistentSequential(String path, Serializable serializable) throws InterruptedException {
        create(path, serializable, CreateMode.PERSISTENT_SEQUENTIAL);
    }

    public void createEphemeral(final String path) throws InterruptedException {
        create(path, null, CreateMode.EPHEMERAL);
    }

    public String create(final String path, Serializable serializable, final CreateMode mode) throws InterruptedException {
        assert path != null;
        final byte[] data = serializable == null ? null : toByteArray(serializable);

        return retryUntilConnected(new Callable<String>() {

            @Override
            public String call() throws Exception {
                return _connection.create(path, data, mode);
            }
        });
    }

    private byte[] toByteArray(Serializable serializable) {
        try {
            ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
            ObjectOutputStream stream = new ObjectOutputStream(byteArrayOS);
            stream.writeObject(serializable);
            stream.close();
            return byteArrayOS.toByteArray();
        } catch (IOException e) {
            throw new ZkMarshallingError(e);
        }
    }

    public void createEphemeral(final String path, final Serializable serializable) throws InterruptedException {
        create(path, serializable, CreateMode.EPHEMERAL);
    }

    public String createEphemeralSequential(final String path, final Serializable serializable) throws InterruptedException {
        return create(path, serializable, CreateMode.EPHEMERAL_SEQUENTIAL);
    }

    public void process(WatchedEvent event) {
        LOG.debug("Received event: " + event);
        _zookeeperEventThread = Thread.currentThread();

        boolean stateChanged = event.getPath() == null;
        boolean znodeChanged = event.getPath() != null;
        boolean dataChanged = event.getType() == EventType.NodeDataChanged || event.getType() == EventType.NodeDeleted || event.getType() == EventType.NodeCreated
                || event.getType() == EventType.NodeChildrenChanged;

        getEventLock().lock();
        try {

            // We might have to install child change event listener if a new node was created
            if (getShutdownTrigger()) {
                LOG.debug("ignoring event '{" + event.getType() + " | " + event.getPath() + "}' since shutdown triggered");
                return;
            }
            if (stateChanged) {
                processStateChanged(event);
            }
            if (dataChanged) {
                processDataOrChildChange(event);
            }
        } finally {
            if (stateChanged) {
                getEventLock().getStateChangedCondition().signalAll();

                // If the session expired we have to signal all conditions, because watches might have been removed and there is no guarantee that those
                // conditions will be signaled at all after an Expired event
                // TODO PVo write a test for this
                if (event.getState() == KeeperState.Expired) {
                    getEventLock().getZNodeEventCondition().signalAll();
                    getEventLock().getDataChangedCondition().signalAll();
                    // We also have to notify all listeners that something might have changed
                    fireAllEvents();
                }
            }
            if (znodeChanged) {
                getEventLock().getZNodeEventCondition().signalAll();
            }
            if (dataChanged) {
                getEventLock().getDataChangedCondition().signalAll();
            }
            getEventLock().unlock();
            LOG.debug("Leaving process event");
        }
    }

    private void fireAllEvents() {
        for (Entry<String, Set<IZkChildListener>> entry : _childListener.entrySet()) {
            fireChildChangedEvents(entry.getKey(), entry.getValue());
        }
        for (Entry<String, Set<IZkDataListener>> entry : _dataListener.entrySet()) {
            fireDataChangedEvents(entry.getKey(), entry.getValue());
        }
    }

    public List<String> getChildren(String path) throws InterruptedException {
        return getChildren(path, hasListeners(path));
    }

    private List<String> getChildren(final String path, final boolean watch) throws InterruptedException {
        return retryUntilConnected(new Callable<List<String>>() {
            @Override
            public List<String> call() throws Exception {
                return _connection.getChildren(path, watch);
            }
        });
    }

    public int countChildren(String path) throws InterruptedException {
        // TODO PVo this is not safe
        int childCount = 0;
        if (exists(path)) {
            childCount = getChildren(path).size();
        }
        return childCount;
    }

    private boolean exists(final String path, final boolean watch) throws InterruptedException {
        return retryUntilConnected(new Callable<Boolean>() {

            @Override
            public Boolean call() throws Exception {
                return _connection.exists(path, watch);
            }
        });
    }

    public boolean exists(final String path) throws InterruptedException {
        return exists(path, hasListeners(path));
    }

    private void processStateChanged(WatchedEvent event) {
        LOG.warn("zookeeper state changed (" + event.getState() + ")");
        setCurrentState(event.getState());
        if (getShutdownTrigger()) {
            return;
        }
        try {
            fireStateChangedEvent(event.getState());

            if (event.getState() == KeeperState.Expired) {
                reconnect();
                fireNewSessionEvents();
            }
        } catch (final Exception e) {
            throw new RuntimeException("Exception while restarting zk client", e);
        }
    }

    private void fireNewSessionEvents() {
        for (final IZkStateListener stateListener : _stateListener) {
            _eventThread.send(new ZkEvent("New session event sent to " + stateListener.getClass().getName()) {

                @Override
                public void run() throws Exception {
                    stateListener.handleNewSession();
                }
            });
        }
    }

    private void fireStateChangedEvent(final KeeperState state) {
        for (final IZkStateListener stateListener : _stateListener) {
            _eventThread.send(new ZkEvent("State changed to " + state + " sent to " + stateListener.getClass().getName()) {

                @Override
                public void run() throws Exception {
                    stateListener.handleStateChanged(state);
                }
            });
        }
    }

    private boolean hasListeners(String path) {
        Set<IZkDataListener> dataListeners = _dataListener.get(path);
        if (dataListeners != null && dataListeners.size() > 0) {
            return true;
        }
        Set<IZkChildListener> childListeners = _childListener.get(path);
        if (childListeners != null && childListeners.size() > 0) {
            return true;
        }
        return false;
    }

    public boolean deleteRecursive(String path) throws InterruptedException {
        List<String> children;
        try {
            children = getChildren(path, false);
        } catch (ZkNoNodeException e) {
            return true;
        }

        for (String subPath : children) {
            if (!deleteRecursive(path + "/" + subPath)) {
                return false;
            }
        }

        return delete(path);
    }

    private void processDataOrChildChange(WatchedEvent event) {
        // ZkEventType eventType = ZkEventType.getMappedType(event.getType());
        final String path = event.getPath();

        if (event.getType() == EventType.NodeChildrenChanged || event.getType() == EventType.NodeCreated || event.getType() == EventType.NodeDeleted) {
            Set<IZkChildListener> childListeners = _childListener.get(path);
            if (childListeners != null && !childListeners.isEmpty()) {
                fireChildChangedEvents(path, childListeners);
            }
        }

        if (event.getType() == EventType.NodeDataChanged || event.getType() == EventType.NodeDeleted || event.getType() == EventType.NodeCreated) {
            Set<IZkDataListener> listeners = _dataListener.get(path);
            if (listeners != null && !listeners.isEmpty()) {
                fireDataChangedEvents(event.getPath(), listeners);
            }
        }
    }

    private void fireDataChangedEvents(final String path, Set<IZkDataListener> listeners) {
        for (final IZkDataListener listener : listeners) {
            _eventThread.send(new ZkEvent("Data of " + path + " changed sent to " + listener.getClass().getName()) {

                @Override
                public void run() throws Exception {
                    // reinstall watch
                    exists(path, true);
                    try {
                        Serializable data = readData(path, true);
                        listener.handleDataChange(path, data);
                    } catch (ZkNoNodeException e) {
                        listener.handleDataDeleted(path);
                    }
                }
            });
        }
    }

    private void fireChildChangedEvents(final String path, Set<IZkChildListener> childListeners) {
        try {
            // reinstall the watch
            for (final IZkChildListener listener : childListeners) {
                _eventThread.send(new ZkEvent("Children of " + path + " changed sent to " + listener.getClass().getName()) {

                    @Override
                    public void run() throws Exception {
                        try {
                            // if the node doesn't exist we should listen for the root node to reappear
                            exists(path);
                            List<String> children = getChildren(path);
                            listener.handleChildChange(path, children);
                        } catch (ZkNoNodeException e) {
                            listener.handleChildChange(path, null);
                        }
                    }
                });
            }
        } catch (Exception e) {
            LOG.error("Failed to fire child changed event. Unable to getChildren.  ", e);
        }
    }

    public boolean waitUntilExists(String path, TimeUnit timeUnit, long time) throws InterruptedException {
        Date timeout = new Date(System.currentTimeMillis() + timeUnit.toMillis(time));
        LOG.info("Waiting until znode '" + path + "' becomes available.");
        if (exists(path)) {
            return true;
        }
        getEventLock().lockInterruptibly();
        try {
            while (!exists(path, true)) {
                boolean gotSignal = getEventLock().getZNodeEventCondition().awaitUntil(timeout);
                if (!gotSignal) {
                    return false;
                }
            }
            return true;
        } finally {
            getEventLock().unlock();
        }
    }

    protected Set<IZkDataListener> getDataListener(String path) {
        return _dataListener.get(path);
    }

    public void showFolders(OutputStream output) throws InterruptedException {
        final int level = 1;
        final StringBuilder builder = new StringBuilder();
        final String startPath = "/";
        addChildrenToStringBuilder(level, builder, startPath);
        try {
            output.write(builder.toString().getBytes());
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private void addChildrenToStringBuilder(final int level, final StringBuilder builder, final String startPath) throws InterruptedException {
        final List<String> children = getChildren(startPath);
        for (final String node : children) {
            builder.append(getSpaces(level - 1) + "'-" + "+" + node + "\n");

            String nestedPath;
            if (startPath.endsWith("/")) {
                nestedPath = startPath + node;
            } else {
                nestedPath = startPath + "/" + node;
            }

            addChildrenToStringBuilder(level + 1, builder, nestedPath);
        }
    }

    private String getSpaces(final int level) {
        String s = "";
        for (int i = 0; i < level; i++) {
            s += "  ";
        }
        return s;
    }

    public void waitUntilConnected() throws InterruptedException {
        waitUntilConnected(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    public boolean waitUntilConnected(long time, TimeUnit timeUnit) throws InterruptedException {
        return waitForKeeperState(KeeperState.SyncConnected, time, timeUnit);
    }

    public boolean waitForKeeperState(KeeperState keeperState, long time, TimeUnit timeUnit) throws InterruptedException {
        if (_zookeeperEventThread != null && Thread.currentThread() == _zookeeperEventThread) {
            throw new IllegalArgumentException("Must not be done in the zookeeper event thread.");
        }
        Date timeout = new Date(System.currentTimeMillis() + timeUnit.toMillis(time));

        LOG.info("Waiting for keeper state " + keeperState);
        getEventLock().lockInterruptibly();
        try {
            boolean stillWaiting = true;
            while (_currentState != keeperState) {
                if (!stillWaiting) {
                    return false;
                }
                stillWaiting = getEventLock().getStateChangedCondition().awaitUntil(timeout);
            }
            LOG.info("State is " + _currentState);
            return true;
        } finally {
            getEventLock().unlock();
        }
    }

    public <T> T retryUntilConnected(Callable<T> callable) throws InterruptedException {
        if (_zookeeperEventThread != null && Thread.currentThread() == _zookeeperEventThread) {
            throw new IllegalArgumentException("Must not be done in the zookeeper event thread.");
        }
        while (true) {
            try {
                return callable.call();
            } catch (ConnectionLossException e) {
                // we give the event thread some time to update the status to 'Disconnected'
                Thread.yield();
                waitUntilConnected();
            } catch (SessionExpiredException e) {
                // we give the event thread some time to update the status to 'Expired'
                Thread.yield();
                waitUntilConnected();
            } catch (KeeperException e) {
                throw ZkException.create(e);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw ExceptionUtil.convertToRuntimeException(e);
            }
        }
    }

    public void setCurrentState(KeeperState currentState) {
        getEventLock().lock();
        try {
            _currentState = currentState;
        } finally {
            getEventLock().unlock();
        }
    }

    /**
     * Returns a mutex all zookeeper events are synchronized aginst. So in case you need to do something without getting any zookeeper event interruption
     * synchronize against this mutex. Also all threads waiting on this mutex object will be notified on an event.
     * 
     * @return the mutex.
     */
    public ZkLock getEventLock() {
        return _zkEventLock;
    }

    public boolean delete(final String path) throws InterruptedException {
        try {
            retryUntilConnected(new Callable<Object>() {

                @Override
                public Object call() throws Exception {
                    _connection.delete(path);
                    return null;
                }
            });

            return true;
        } catch (ZkNoNodeException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Serializable> T readData(final String path) throws InterruptedException {
        return (T) readData(path, hasListeners(path));
    }

    @SuppressWarnings("unchecked")
    private <T extends Serializable> T readData(final String path, final boolean watch) throws InterruptedException {
        byte[] data = retryUntilConnected(new Callable<byte[]>() {

            @Override
            public byte[] call() throws Exception {
                return _connection.readData(path, watch);
            }
        });
        return (T) readSerializable(data);
    }

    @SuppressWarnings("unchecked")
    private <T extends Serializable> T readSerializable(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            ObjectInputStream inputStream = new ObjectInputStream(new ByteArrayInputStream(data));
            Object object = inputStream.readObject();
            return (T) object;
        } catch (ClassNotFoundException e) {
            throw new ZkMarshallingError("Unable to find object class.", e);
        } catch (IOException e) {
            throw new ZkMarshallingError(e);
        }
    }

    public void writeData(final String path, Serializable serializable) throws InterruptedException {
        final byte[] data = toByteArray(serializable);
        retryUntilConnected(new Callable<Object>() {

            @Override
            public Object call() throws Exception {
                _connection.writeData(path, data);
                return null;
            }
        });
    }

    public void watchForData(final String path) throws InterruptedException {
        retryUntilConnected(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                _connection.exists(path, true);
                return null;
            }
        });
    }

    public void watchForChilds(final String path) throws InterruptedException {
        if (_zookeeperEventThread != null && Thread.currentThread() == _zookeeperEventThread) {
            throw new IllegalArgumentException("Must not be done in the zookeeper event thread.");
        }
        retryUntilConnected(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                exists(path, true);
                try {
                    getChildren(path, true);
                } catch (ZkNoNodeException e) {
                    // ignore, the "exists" watch will listen for the parent node to appear
                }
                return null;
            }
        });
    }

    public void connect(final long maxMsToWaitUntilConnected, Watcher watcher) {
        boolean started = false;
        try {
            getEventLock().lockInterruptibly();
            setShutdownTrigger(false);
            _eventThread = new ZkEventThread();
            _eventThread.start();
            _connection.connect(watcher);

            LOG.debug("Awaiting connection to Zookeeper server");
            if (!waitUntilConnected(maxMsToWaitUntilConnected, TimeUnit.MILLISECONDS)) {
                throw new ZkTimeoutException("Unable to connect to zookeeper server within timeout: " + maxMsToWaitUntilConnected);
            }
            started = true;
        } catch (InterruptedException e) {
            States state = _connection.getZookeeperState();
            throw new IllegalStateException("Not connected with zookeeper server yet. Current state is " + state);
        } finally {
            getEventLock().unlock();

            // we should close the zookeeper instance, otherwise it would keep
            // on trying to connect
            if (!started) {
                try {
                    close();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }
    }

    public void close() throws InterruptedException {
        LOG.info("Closing ZkClient...");
        getEventLock().lock();
        try {
            setShutdownTrigger(true);
            _eventThread.interrupt();
            _eventThread.join();
            _connection.close();
        } finally {
            getEventLock().unlock();
        }
        LOG.info("Closing ZkClient...done");
    }

    private void reconnect() throws InterruptedException {
        getEventLock().lock();
        try {
            _connection.close();
            _connection.connect(this);
        } finally {
            getEventLock().unlock();
        }
    }

    public void setShutdownTrigger(boolean triggerState) {
        _shutdownTriggered = triggerState;
    }

    public boolean getShutdownTrigger() {
        return _shutdownTriggered;
    }
}
