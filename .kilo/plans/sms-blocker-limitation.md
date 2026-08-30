# SMS Blocking Limitation

Android does not provide a public API for blocking SMS messages without carrier integration or being the default SMS app.

## Current Implementation
- Call blocking is implemented via `CallScreeningService` (Android 7.0+)
- SMS blocking is NOT implemented due to platform limitations

## Possible Approaches
1. **CarrierMessagingService**: Requires carrier-specific implementation, not suitable for general Play Store apps.
2. **SmsRetriever API**: Can read incoming SMS but cannot block them.
3. **Default SMS App**: Becoming the default SMS app allows full control over SMS, but requires significant implementation effort and user education.
4. **Documentation**: Inform users about the limitation and suggest alternative protections.

## Recommendation
For MVP, document the limitation and focus on call blocking via `CallScreeningService`. Consider implementing full SMS app functionality in a future version if required by users.
