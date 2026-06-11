//! D303 — exponential backoff for transient sync failures: 1s → 60s cap.

use std::time::Duration;

#[derive(Debug, Default)]
pub struct Backoff {
    attempt: u32,
}

impl Backoff {
    pub const MAX_DELAY: Duration = Duration::from_secs(60);

    pub fn new() -> Self {
        Self::default()
    }

    /// Delay before the next retry: 1, 2, 4, 8, … capped at 60 seconds.
    pub fn next_delay(&mut self) -> Duration {
        let delay = Duration::from_secs(1 << self.attempt.min(6));
        self.attempt = self.attempt.saturating_add(1);
        delay.min(Self::MAX_DELAY)
    }

    /// Call after a successful request so the next failure starts at 1s again.
    pub fn reset(&mut self) {
        self.attempt = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delays_double_from_one_second_and_cap_at_sixty() {
        let mut backoff = Backoff::new();
        let secs: Vec<u64> = (0..8).map(|_| backoff.next_delay().as_secs()).collect();

        assert_eq!(secs, vec![1, 2, 4, 8, 16, 32, 60, 60]);
    }

    #[test]
    fn reset_restarts_the_schedule() {
        let mut backoff = Backoff::new();
        backoff.next_delay();
        backoff.next_delay();

        backoff.reset();

        assert_eq!(backoff.next_delay().as_secs(), 1);
    }
}
