// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Ctrl-C for the command that is running.
//!
//! The core polls a [`Cancel`] token and never sees signals. This module owns
//! the one token for the process and the handler that cancels it. The first
//! Ctrl-C asks the operation to stop at its next check, so a cancelled install
//! removes its temp dirs instead of leaving them the way a killed process
//! does. A second Ctrl-C exits at once: a download blocked inside a read has
//! not reached that check yet, and it must still be killable.

use ketch_core::cancel::Cancel;
use std::sync::OnceLock;

static TOKEN: OnceLock<Cancel> = OnceLock::new();

/// What one Ctrl-C means, given how many have already arrived.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ask {
    /// The first one. Long work stops at its next check.
    Stop,
    /// Another one before that check. Exit immediately.
    Exit,
}

/// The token every context and install request for this process shares.
pub fn token() -> Cancel {
    TOKEN.get_or_init(Cancel::new).clone()
}

/// Record an interrupt. The first call cancels `cancel`; a later one says to exit.
pub fn note(cancel: &Cancel) -> Ask {
    if cancel.is_cancelled() {
        Ask::Exit
    } else {
        cancel.cancel();
        Ask::Stop
    }
}

/// Arm Ctrl-C. A failure leaves the platform default, which still ends the process:
/// a handler is already installed, or this process cannot have one.
pub fn install() {
    let cancel = token();
    let _ = ctrlc::set_handler(move || {
        if note(&cancel) == Ask::Exit {
            std::process::exit(130);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_first_interrupt_stops_and_the_second_says_exit() {
        let cancel = Cancel::new();
        assert_eq!(note(&cancel), Ask::Stop);
        assert!(cancel.is_cancelled());
        assert_eq!(note(&cancel), Ask::Exit);
    }
}
