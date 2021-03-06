/*
 * Copyright 2017 Nordstrom, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nordstrom.xrpc.demos.dino;

import com.nordstrom.xrpc.demos.dino.proto.Dino;
import java.io.IOException;

public class DinoDecoder {
  public static void main(String[] args) throws IOException {
    byte[] bytes = new byte[System.in.available()];
    System.in.read(bytes, 0, bytes.length);
    Dino dino = Dino.parseFrom(bytes);
    System.out.println(dino);
  }
}
