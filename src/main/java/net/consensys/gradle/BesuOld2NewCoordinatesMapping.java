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
    try (final var br =
        new BufferedReader(
            new InputStreamReader(
                BesuOld2NewCoordinatesMapping.class.getResourceAsStream(
                    "/maven-coordinates-mapping.txt")))) {
      br.lines()
          .forEach(
              line -> {
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
