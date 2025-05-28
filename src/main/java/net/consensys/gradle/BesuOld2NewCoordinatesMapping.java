package net.consensys.gradle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class BesuOld2NewCoordinatesMapping {
  static final Map<String, String> OLD_2_NEW_COORDINATES_MAP;

  static {
    final var map = new HashMap<String, String>();
    try (final var br = new BufferedReader(new InputStreamReader(
        BesuOld2NewCoordinatesMapping.class.getResourceAsStream("/maven-coordinates-mapping.txt")))) {
      br.lines().forEach(line -> {
        final var splitLine = line.split("\\s+");
        final var newCoordinate = splitLine[0];
        final var oldCoordinate = splitLine[1];
        if (!newCoordinate.equals(oldCoordinate)) {
          map.put(oldCoordinate, newCoordinate);
        }
      });
      OLD_2_NEW_COORDINATES_MAP = Collections.unmodifiableMap(map);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static Map<String, String> getOld2NewCoordinates() {
    return OLD_2_NEW_COORDINATES_MAP;
  }
}
