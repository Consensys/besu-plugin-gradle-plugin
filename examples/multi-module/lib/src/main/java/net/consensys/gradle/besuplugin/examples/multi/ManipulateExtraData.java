package net.consensys.gradle.besuplugin.examples.multi;

import org.apache.tuweni.bytes.Bytes;

import java.nio.charset.StandardCharsets;

public class ManipulateExtraData {
  public static String decodeAsText(Bytes rawExtraData) {
    return new String(rawExtraData.toArray(), StandardCharsets.UTF_8);
  }
}
