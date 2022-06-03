package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;


public abstract class OldRecycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance (OldRecycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle () {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger (Integer.MIN_VALUE);
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement ();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    private static final int LINK_CAPACITY;
    private static final int RATIO;
    private static final int DELAYED_QUEUE_RATIO;
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> () {
                @Override
                protected Map<Stack<?>, WeakOrderQueue> initialValue() {
                    return new WeakHashMap<Stack<?>, WeakOrderQueue> ();
                }
            };

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt ("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt ("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max (2,
                SystemPropertyUtil.getInt ("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max (0,
                SystemPropertyUtil.getInt ("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors () * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo (
                max (SystemPropertyUtil.getInt ("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = max (0, SystemPropertyUtil.getInt ("io.netty.recycler.ratio", 8));
        DELAYED_QUEUE_RATIO = max (0, SystemPropertyUtil.getInt ("io.netty.recycler.delayedQueue.ratio", RATIO));

        INITIAL_CAPACITY = min (DEFAULT_MAX_CAPACITY_PER_THREAD, 256);

        if (logger.isDebugEnabled ()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug ("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug ("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug ("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug ("-Dio.netty.recycler.ratio: disabled");
                logger.debug ("-Dio.netty.recycler.delayedQueue.ratio: disabled");
            } else {
                logger.debug ("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug ("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug ("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug ("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug ("-Dio.netty.recycler.delayedQueue.ratio: {}", DELAYED_QUEUE_RATIO);
            }
        }
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int interval;
    private final int maxDelayedQueuesPerThread;
    private final int delayedQueueInterval;
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>> () {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T> (OldRecycler.this, Thread.currentThread (), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread, delayedQueueInterval);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get () == Thread.currentThread ()) {
                if (DELAYED_RECYCLED.isSet ()) {
                    DELAYED_RECYCLED.get ().remove (value);
                }
            }
        }
    };

    protected OldRecycler() {
        this (DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected OldRecycler(int maxCapacityPerThread) {
        this (maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected OldRecycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this (maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected OldRecycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                          int ratio, int maxDelayedQueuesPerThread) {
        this (maxCapacityPerThread, maxSharedCapacityFactor, ratio, maxDelayedQueuesPerThread,
                DELAYED_QUEUE_RATIO);
    }

    protected OldRecycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                          int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        interval = max (0, ratio);
        delayedQueueInterval = max (0, delayedQueueRatio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max (1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max (0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        if (maxCapacityPerThread == 0) {
            return newObject ((Handle<T>) NOOP_HANDLE);
        }
        Stack<T> stack = threadLocal.get ();
        DefaultHandle<T> handle = stack.pop ();
        if (handle == null) {
            handle = stack.newHandle ();
            handle.value = newObject (handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        //handle的stack的recycler和this不同
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle (o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get ().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get ().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> extends ObjectPool.Handle<T> {
    }

    @SuppressWarnings("unchecked")
    private static final class DefaultHandle<T> implements Handle<T> {
        private static final AtomicIntegerFieldUpdater<DefaultHandle<?>> LAST_RECYCLED_ID_UPDATER;

        static {
            AtomicIntegerFieldUpdater<?> updater = AtomicIntegerFieldUpdater.newUpdater (
                    DefaultHandle.class, "lastRecycledId");
            LAST_RECYCLED_ID_UPDATER = (AtomicIntegerFieldUpdater<DefaultHandle<?>>) updater;
        }

        volatile int lastRecycledId;
        int recycleId;

        boolean hasBeenRecycled;

        Stack<?> stack;
        Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException ("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException ("recycled already");
            }

            stack.push (this);
        }

        public boolean compareAndSetLastRecycledId(int expectLastRecycledId, int updateLastRecycledId) {
            // Use "weak…" because we do not need synchronize-with ordering, only atomicity.
            // Also, spurious failures are fine, since no code should rely on recycling for correctness.
            return LAST_RECYCLED_ID_UPDATER.weakCompareAndSet (this, expectLastRecycledId, updateLastRecycledId);
        }
    }

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue ();
        // chain of data items
        private final Head head;
        private final int id = ID_GENERATOR.getAndIncrement ();
        private final int interval;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private int handleRecycleCount;

        private WeakOrderQueue() {
            super (null);
            head = new Head (null);
            interval = 0;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super (thread);
            tail = new Link ();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head (stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            if (!Head.reserveSpaceForLink (stack.availableSharedCapacity)) {
                return null;
            }
            final WeakOrderQueue queue = new WeakOrderQueue (stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead (queue);

            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink ();
            next = null;
        }

        void add(DefaultHandle<?> handle) {
            if (!handle.compareAndSetLastRecycledId (0, id)) {
                // Separate threads could be racing to add the handle to each their own WeakOrderQueue.
                // We only add the handle to the queue if we win the race and observe that lastRecycledId is zero.
                return;
            }

            // While we also enforce the recycling ratio when we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control
            if (!handle.hasBeenRecycled) {
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the item to prevent from recycling too aggressively.
                    return;
                }
                handleRecycleCount = 0;
            }

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get ()) == LINK_CAPACITY) {
                Link link = head.newLink ();
                if (link == null) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = link;

                writeIndex = tail.get ();
            }
            tail.elements[writeIndex] = handle;
            //将stack置空，表示当前对象已被回收
            //并且并不是由Stack所属线程所回收
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet (writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get ();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        // 遍历WeakOrderQueue中的Link，将他们回收的对象迁移到Stack中，如果发现被使用完的Link，那么将从链表中剔除并回收容量。
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head.link;
            if (head == null) {
                return false;
            }

            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                head = head.next;
                //回收空间并重新设置link
                this.head.relink (head);
            }

            final int srcStart = head.readIndex;
            int srcEnd = head.get ();
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;

            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity (expectedCapacity);
                srcEnd = min (srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException ("recycled already");
                    }
                    srcElems[i] = null;

                    //流控
                    if (dst.dropHandle (element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.relink (head.next);
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        static final class Link extends AtomicInteger {
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            int readIndex;
            Link next;
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        // Head封装了Link构成的链表
        private static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            //Link空间总和有限制，所以要判断
            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (; ; ) {
                    int available = availableSharedCapacity.get ();
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet (available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             * 释放所有空间
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                while (head != null) {
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace (reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet (space);
            }

            void relink(Link link) {
                reclaimSpace (LINK_CAPACITY);
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             */
            Link newLink() {
                return reserveSpaceForLink (availableSharedCapacity) ? new Link () : null;
            }
        }
    }

    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final OldRecycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        // 持有对应stack的线程
        final WeakReference<Thread> threadRef;
        final AtomicInteger availableSharedCapacity;
        private final int maxDelayedQueues;

        private final int maxCapacity;
        private final int interval;
        private final int delayedQueueInterval;
        DefaultHandle<?>[] elements;
        int size;
        private int handleRecycleCount;
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(OldRecycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues, int delayedQueueInterval) {
            this.parent = parent;
            threadRef = new WeakReference<Thread> (thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger (max (maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min (INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            this.delayedQueueInterval = delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext (head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min (newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf (elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                //如果当前栈中没有值，那么尝试从WeakOrderQueue中查找
                if (!scavenge ()) {
                    return null;
                }
                size = this.size;
                if (size <= 0) {
                    // double check, avoid races
                    return null;
                }
            }
            size--;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            this.size = size;

            //不允许多次回收，每个对象只能被一个线程回收一次
            //可能共享obj，此时从多个地方回收；新版本需要从外部避免这个问题
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException ("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        private boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome ()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        //从cursor开始扫描一部分
        private boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                //尝试将当前扫描到的节点的回收对象转移到Stack的数组中
                if (cursor.transfer (this)) {
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.getNext ();
                //当前节点线程被gc回收后，cursor.owner.get()将返回null
                //判断线程是否被GC了
                if (cursor.get () == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    //判断是否线程被回收了，但是对应的queue还有数据，此时仍然进行转移
                    if (cursor.hasFinalData ()) {
                        for (; ; ) {
                            if (cursor.transfer (this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        cursor.reclaimAllSpaceAndUnlink ();
                        prev.setNext (next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            //记录扫描到的位置
            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread ();
            //检查当前Stack所属线程是否为当前线程，如果是当前线程，那么很简单，直接往Stack中的数组中添加即可
            //如果不是当前线程，那么为了避免多线程带来的加锁操作，这里会创建专属于当前线程的 WeakOrderQueue
            if (threadRef.get () == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow (item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                // 另一个线程进行回收
                pushLater (item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            if (item.recycleId != 0 || !item.compareAndSetLastRecycledId (0, OWN_THREAD_ID)) {
                throw new IllegalStateException ("recycled already");
            }
            item.recycleId = OWN_THREAD_ID;

            int size = this.size;
            if (size >= maxCapacity || dropHandle (item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            if (size == elements.length) {
                elements = Arrays.copyOf (elements, min (size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        private void pushLater(DefaultHandle<?> item, Thread thread) {
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            //从每个线程上获取其Map<Stack<?>, WeakOrderQueue>，key表示当初当前线程回收对象所属的Stack，
            //value表示当前线程回收对象时为当前线程专属创建的WeakOrderQueue。
            //当前线程其他stack创建的queue
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get ();
            WeakOrderQueue queue = delayedRecycled.get (this);//this是创建该对象的线程的stack
            if (queue == null) {
                //如果超过了最大容量，那么将不再回收对象，这里直接put一个空队列标记
                if (delayedRecycled.size () >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put (this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = newWeakOrderQueue (thread)) == null) {
                    // drop object
                    return;
                }
                delayedRecycled.put (this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            queue.add (item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue (this, thread);
        }

        //有一个ratio
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                handleRecycleCount = 0;
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T> (this);
        }
    }
}