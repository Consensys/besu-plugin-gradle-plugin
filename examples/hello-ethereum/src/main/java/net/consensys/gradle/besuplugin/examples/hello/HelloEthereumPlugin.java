package net.consensys.gradle.besuplugin.examples.hello;

import com.google.auto.service.AutoService;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;

@AutoService(BesuPlugin.class)
public class HelloEthereumPlugin implements BesuPlugin {
  @Override
  public void register(final ServiceManager serviceManager) {
    System.out.println("Registering HelloEthereumPlugin");
  }

  @Override
  public void start() {
    System.out.println("Hello Ethereum!");
  }

  @Override
  public void stop() {
    System.out.println("Bye");
  }
}
