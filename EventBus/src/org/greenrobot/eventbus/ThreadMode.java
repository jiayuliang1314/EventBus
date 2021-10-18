/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

/**
 * Each subscriber method has a thread mode, which determines in which thread the method is to be called by EventBus.
 * EventBus takes care of threading independently from the posting thread.
 * 
 * @see EventBus#register(Object)
 * @author Markus
 * ok
 */
public enum ThreadMode {
    /**
     * Subscriber will be called directly in the same thread, which is posting the event. This is the default. Event delivery
     * implies the least overhead because it avoids thread switching completely. Thus this is the recommended mode for
     * simple tasks that are known to complete in a very short time without requiring the main thread. Event handlers
     * using this mode must return quickly to avoid blocking the posting thread, which may be the main thread.
     * 订阅者将在发布事件的同一线程中直接调用。 这是默认设置。 事件传递意味着最少的开销，因为它完全避免了线程切换。
     * 因此，对于已知在很短的时间内完成而不需要主线程的简单任务，这是推荐的模式。 使用此模式的事件处理程序必须快速
     * 返回以避免阻塞可能是主线程的发布线程。
     *
     * 默认的线程模式，在哪个线程发送事件就在对应线程处理事件，避免了线程切换，效率高。
     */
    POSTING,

    /**
     * On Android, subscriber will be called in Android's main thread (UI thread). If the posting thread is
     * the main thread, subscriber methods will be called directly, blocking the posting thread. Otherwise the event
     * is queued for delivery (non-blocking). Subscribers using this mode must return quickly to avoid blocking the main thread.
     * If not on Android, behaves the same as {@link #POSTING}.
     * 在 Android 上，订阅者将在 Android 的主线程（UI 线程）中调用。
     * 如果发帖线程是主线程，则直接调用订阅者方法，阻塞发帖线程。否则，事件将排队等待传递（非阻塞）。
     * 使用这种模式的订阅者必须快速返回以避免阻塞主线程。 如果不是在 Android 上，则行为与 {@link #POSTING} 相同。
     *
     * 如在主线程（UI线程）发送事件，则直接在主线程处理事件；如果在子线程发送事件，则先将事件入队列，然后通过 Handler 切换到主线程，依次处理事件。
     */
    MAIN,

    /**
     * On Android, subscriber will be called in Android's main thread (UI thread). Different from {@link #MAIN},
     * the event will always be queued for delivery. This ensures that the post call is non-blocking.
     * 在 Android 上，订阅者将在 Android 的主线程（UI 线程）中调用。 与 {@link #MAIN} 不同，事件将始终排队等待交付。 这确保了 post 调用是非阻塞的。
     *
     * 无论在哪个线程发送事件，都将事件加入到队列中，然后通过Handler切换到主线程，依次处理事件。
     */
    MAIN_ORDERED,

    /**
     * On Android, subscriber will be called in a background thread. If posting thread is not the main thread, subscriber methods
     * will be called directly in the posting thread. If the posting thread is the main thread, EventBus uses a single
     * background thread, that will deliver all its events sequentially. Subscribers using this mode should try to
     * return quickly to avoid blocking the background thread. If not on Android, always uses a background thread.
     * 在 Android 上，订阅者将在后台线程中调用。 如果发帖线程不是主线程，则将在发帖线程中直接调用订阅者方法。
     * 如果发布线程是主线程，则 EventBus 使用单个后台线程，它将按顺序传递其所有事件。 使用这种模式的订阅者应该尝试快速返回以避免阻塞后台线程。
     * 如果不是在 Android 上，则始终使用后台线程。
     *
     * 与ThreadMode.MAIN相反，如果在子线程发送事件，则直接在子线程处理事件；如果在主线程上发送事件，则先将事件入队列，然后通过线程池处理事件。
     */
    BACKGROUND,

    /**
     * Subscriber will be called in a separate thread. This is always independent from the posting thread and the
     * main thread. Posting events never wait for subscriber methods using this mode. Subscriber methods should
     * use this mode if their execution might take some time, e.g. for network access. Avoid triggering a large number
     * of long running asynchronous subscriber methods at the same time to limit the number of concurrent threads. EventBus
     * uses a thread pool to efficiently reuse threads from completed asynchronous subscriber notifications.
     *
     * 订阅者将在单独的线程中调用。 这始终独立于发布线程和主线程。 使用此模式发布事件从不等待订阅者方法。
     * 如果订阅者方法的执行可能需要一些时间，则应使用此模式，例如 用于网络访问。
     * 避免同时触发大量长时间运行的异步订阅者方法以限制并发线程数。
     * EventBus 使用线程池来有效地重用来自已完成的异步订阅者通知的线程。
     *
     * 与ThreadMode.MAIN_ORDERED相反，无论在哪个线程发送事件，都将事件加入到队列中，然后通过线程池执行事件
     */
    ASYNC
}