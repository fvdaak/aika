/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aika;


/**
 *
 * The suspension hook is used to suspend neurons and logic nodes to an external storage in order to reduce the memory footprint.
 *
 * !!! Important: When using the suspension hook, all references to a neuron or a logic node need to occur through a
 * provider. Otherwise the reference might be outdated.
 *
 * @author Lukas Molzberger
 */
public interface SuspensionHook {


    int getNewId();

    void store(int id, byte[] data);

    byte[] retrieve(int id);
}
