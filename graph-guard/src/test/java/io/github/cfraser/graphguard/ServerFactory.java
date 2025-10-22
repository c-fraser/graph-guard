/*
Copyright 2023 c-fraser

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.github.cfraser.graphguard;

import io.github.cfraser.graphguard.Bolt.Message;
import java.net.URI;
import java.util.concurrent.Executor;
import org.jetbrains.annotations.NotNull;

final class ServerFactory {

  private ServerFactory() {}

  /**
   * Initialize a {@link Server} in <i>Java</i>.
   *
   * @param boltUri the {@link URI} of the graph database
   * @param executor the {@link Executor} for {@link Server.Plugin.Blocking}
   * @return the {@link Server}
   */
  static Server init(URI boltUri, Executor executor) {
    return new Server(
        boltUri,
        new Server.Plugin.Blocking(executor) {
          @NotNull
          @Override
          public Message interceptBlocking(@NotNull String session, @NotNull Bolt.Message message) {
            return message;
          }

          @Override
          public void observeBlocking(@NotNull Server.Event event) {}
        });
  }
}
