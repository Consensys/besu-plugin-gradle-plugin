package net.consensys.gradle.besuplugin.examples.multi;

import com.google.auto.service.AutoService;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.data.AddedBlockContext;
import org.hyperledger.besu.plugin.services.BesuEvents;

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
    final var besuEvents = serviceManager.getService(BesuEvents.class)
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
    System.out.println("Decoded extra data for block %d is: %s".formatted(blockHeader.getNumber(), decodedExtraData));
  }
}
