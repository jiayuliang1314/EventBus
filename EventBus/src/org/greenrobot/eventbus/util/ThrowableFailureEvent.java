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
package org.greenrobot.eventbus.util;

/**
 * A generic failure event, which can be used by apps to propagate thrown exceptions. Also used in conjunction with
 * {@link ErrorDialogManager}.
 *
 * conjunction
 * 连词， 连接词 (如and、but、or); (引起某种结果的事物等的)结合，同时发生; (恒星、行星等的)合;
 *
 *
 suppress	英[səˈpres]
 美[səˈpres]
 v.	镇压; (武力)平定; 压制; 禁止(发表); 查禁; 封锁; 抑制; 控制; 忍住;
 */
public class ThrowableFailureEvent implements HasExecutionScope {
    protected final Throwable throwable;
    protected final boolean suppressErrorUi;//？
    private Object executionContext;

    public ThrowableFailureEvent(Throwable throwable) {
        this.throwable = throwable;
        suppressErrorUi = false;
    }

    /**
     * @param suppressErrorUi
     *            true indicates to the receiver that no error UI (e.g. dialog) should now displayed.
     */
    public ThrowableFailureEvent(Throwable throwable, boolean suppressErrorUi) {
        this.throwable = throwable;
        this.suppressErrorUi = suppressErrorUi;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public boolean isSuppressErrorUi() {
        return suppressErrorUi;
    }

    public Object getExecutionScope() {
        return executionContext;
    }

    public void setExecutionScope(Object executionContext) {
        this.executionContext = executionContext;
    }
    
}
