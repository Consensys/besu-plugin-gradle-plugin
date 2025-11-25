/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package net.consensys.gradle.besuplugin.examples.multi;

import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.data.AddedBlockContext;
import org.hyperledger.besu.plugin.services.BesuEvents;

import com.google.auto.service.AutoService;

@AutoService(BesuPlugin.class)
public class PrintExtraDataPlugin implements BesuPlugin, BesuEvents.BlockAddedListener {
  private ServiceManager serviceManager;

  @Override
  public void register(final ServiceManager serviceManager) {
    System.out.println("Registering PrintExtraDataPlugin");
    this.serviceManager = serviceManager;
  }

  @Override
  public void start() {
    final var besuEvents =
        serviceManager
            .getService(BesuEvents.class)
            .orElseThrow(() -> new IllegalStateException("No BesuEvents service found"));

    besuEvents.addBlockAddedListener(this);
  }

  @Override
  public void stop() {
    System.out.println("Bye");
  }

  @Override
  public void onBlockAdded(final AddedBlockContext addedBlockContext) {
    final var blockHeader = addedBlockContext.getBlockHeader();
    final var decodedExtraData = ManipulateExtraData.decodeAsText(blockHeader.getExtraData());
    System.out.println(
        "Decoded extra data for block %d is: %s"
            .formatted(blockHeader.getNumber(), decodedExtraData));
  }
}
