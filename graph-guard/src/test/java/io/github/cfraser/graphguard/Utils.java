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
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

final class Utils {

  private Utils() {}

  /**
   * Initialize a {@link Server} in <i>Java</i>.
   *
   * @param boltUrl the URL of the graph database
   * @return the {@link Server}
   */
  static Server server(String boltUrl) {
    return new Server(
        URI.create(boltUrl),
        new Server.Plugin.Async() {

          @NotNull
          @Override
          public CompletableFuture<Message> interceptAsync(@NotNull Bolt.Message message) {
            return CompletableFuture.completedFuture(message);
          }

          @NotNull
          @Override
          public CompletableFuture<Void> observeAsync(@NotNull Server.Event event) {
            return CompletableFuture.completedFuture(null);
          }
        });
  }
}
