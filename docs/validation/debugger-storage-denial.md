# Debugger review buttons and blocked browser storage

An owner using Chrome on Windows reported reaching the completed capture review with consent selected, then getting no response from Send or Download. Console messages included `Access to storage is not allowed from this context`. The supplied logs do not identify the responsible setting or extension; `content.js` may be extension code. We have not reproduced the owner's exact Windows environment.

## Reproduced failure

The public debugger bundle fetched on 2026-10-26 has the same environment-check path as the repository: `action()` sets `busy = true`, then calls `validateEnvironment()` **before** entering its try/finally. The latter reads `navigator.serviceWorker.controller`. If that property access throws, the click rejects without displaying a controlled error and never resets `busy`; later clicks return immediately. The initial service-worker event subscription can also abort initialization if access is denied at page load.

Regression fixtures that throw on service-worker access after verified capture fail on the old code and pass on the fix. They verify both export actions, exact equality of the downloaded/submitted capture, continued consent enforcement, and no display of exception details. Cases cover denial at page load, denial of the service-worker container or its controller, and permission recovery installing the controller-change monitor before USB becomes available.

## Behavior after the fix

- Browser-check failures display a fixed, non-sensitive explanation and keep new USB work disabled.
- Completed, cleanup-verified captures remain in memory and can be downloaded or submitted without storage access.
- Action initialization is inside the error-handler/finally boundary, so a failure cannot permanently retain the busy flag.
- The service-worker observer is installed during guarded validation, retried after access recovers, and not duplicated on each button press.
- Capture consent, cleanup verification, recovery journaling, redaction and the existing network policy are unchanged.

Validation: all 105 web tests, the web production build, and the existing headless Chrome browser check with added SecurityError getter cases. The browser check covers six widths (320–1280px), CSP, other-host refusal, existing-worker refusal, accessibility checks and zero uncaught page errors. Phone operations and server submissions are stubbed; no real recording or submission is made.

This fixes a demonstrated deadlock consistent with the report. Confirmation on the affected user's browser remains necessary after deployment. It does not establish that every `content.js` message comes from Volt.
