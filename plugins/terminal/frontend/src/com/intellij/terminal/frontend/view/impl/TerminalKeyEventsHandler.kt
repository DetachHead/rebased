package com.intellij.terminal.frontend.view.impl

import org.jetbrains.annotations.ApiStatus
import java.awt.event.KeyEvent

@ApiStatus.Internal
interface TerminalKeyEventsHandler {
  fun keyTyped(e: KeyEvent) {}
  fun keyPressed(e: KeyEvent) {}

  /** A release reaches the shell only under the Kitty keyboard protocol; the handler consumes it only then. */
  fun keyReleased(e: KeyEvent) {}

  /** The terminal lost the keyboard focus: the releases of the keys held now go to another component. */
  fun focusLost() {}
}

internal fun TerminalKeyEventsHandler.handleKeyEvent(e: KeyEvent) {
  when (e.id) {
    KeyEvent.KEY_TYPED -> keyTyped(e)
    KeyEvent.KEY_PRESSED -> keyPressed(e)
    KeyEvent.KEY_RELEASED -> keyReleased(e)
  }
}
