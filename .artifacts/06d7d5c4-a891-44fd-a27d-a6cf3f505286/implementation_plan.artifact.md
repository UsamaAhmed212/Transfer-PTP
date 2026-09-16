# Implementation Plan - PIN Entry Web Server

The goal is to update the existing Ktor web server to serve a specific PIN entry page instead of the current file browser. The design of the page is provided by the user.

## User Review Required

> [!IMPORTANT]
> The existing file browser functionality will be replaced by the PIN entry screen as requested ("nothing else"). I will implement a basic verification flow that shows a success message when the correct PIN is entered.

## Proposed Changes

### app Module

#### [MODIFY] [FileServer.kt](file:///D:/Android Development/Transfer-PTP/app/src/main/java/com/example/transferptp/FileServer.kt)
- Add a `currentPin` property to the `FileServer` class.
- Generate a new 4-digit PIN when the server starts.
- Update the `/` route to serve the provided HTML design.
- Add JavaScript to the HTML to handle OTP input focus and the "Verify" button action.
- Add a POST `/verify` endpoint to check the entered PIN.
- Remove (or comment out) the previous file browsing routes (`/thumbnail`, `/stream`, `/download`) to comply with "nothing else".

#### [MODIFY] [MainActivity.kt](file:///D:/Android Development/Transfer-PTP/app/src/main/java/com/example/transferptp/MainActivity.kt)
- Update the UI to display the current PIN to the user so they know what to enter in the browser.

## Verification Plan

### Automated Tests
- None requested, but I will verify the build.

### Manual Verification
1. Start the server from the app.
2. Note the IP address and PIN displayed on the app screen.
3. Open a browser on a device in the same network and navigate to the IP address.
4. Verify the PIN entry screen matches the provided design.
5. Enter the correct PIN and verify the success screen appears.
6. Enter an incorrect PIN and verify it shows an error (or stays on the same page with a shake animation if implemented).
