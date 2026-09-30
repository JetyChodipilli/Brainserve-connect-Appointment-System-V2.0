import { expect, test } from "@playwright/test";

const appointment = {
  referenceNumber: "BSA-7M4K-26Q9",
  type: "EMPLOYEE_VISIT",
  status: "APPROVED",
  hostReference: "11111111-1111-1111-1111-111111111111",
  slotStart: "2026-07-29T04:00:00.000Z",
  slotEnd: "2026-07-29T04:30:00.000Z",
  visitorDisplayName: "A***",
};

test("appointment cancellation requires the emailed OTP in a real browser flow", async ({ page }) => {
  let cancellationOtpRequests = 0;
  let submittedOtp = "";

  await page.route("http://backend.invalid/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/v1/public/company-profile") {
      await route.fulfill({ json: {
          name: "BrainServe Private Limited",
          emailDomain: "brainserve.in",
          hqAddress: "Hyderabad, Telangana, India",
          supportEmail: "support@brainserve.in",
        } });
      return;
    }
    if (path === "/api/v1/public/hosts") {
      await route.fulfill({ json: [] });
      return;
    }
    if (path.endsWith("/cancel/request-otp") && request.method() === "POST") {
      cancellationOtpRequests += 1;
      await route.fulfill({ status: 204 });
      return;
    }
    if (path.endsWith("/cancel") && request.method() === "POST") {
      submittedOtp = (request.postDataJSON() as { otp: string }).otp;
      await route.fulfill({ json: { ...appointment, status: "CANCELLED" } });
      return;
    }
    if (path.endsWith(`/${appointment.referenceNumber}`) && request.method() === "GET") {
      await route.fulfill({ json: appointment });
      return;
    }
    if (path.endsWith(`/${appointment.referenceNumber}/pass`)) {
      await route.fulfill({ status: 404, json: { detail: "Pass unavailable in test" } });
      return;
    }
    await route.fulfill({ status: 404, json: { detail: `Unhandled test request: ${path}` } });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Track appointment" }).click();
  await page.getByLabel("Tracking reference").fill(appointment.referenceNumber);
  const trackedResponse = page.waitForResponse((response) =>
      response.url() === `http://backend.invalid/api/v1/public/appointments/${appointment.referenceNumber}`
      && response.request().method() === "GET");
  const [tracking] = await Promise.all([
    trackedResponse,
    page.getByRole("button", { name: "Track", exact: true }).click(),
  ]);
  expect(tracking.status()).toBe(200);
  await expect(page.getByText(appointment.referenceNumber)).toBeVisible();

  await page.getByRole("button", { name: "Cancel appointment" }).click();
  await expect(page.getByText("Cancellation code sent")).toBeVisible();
  await expect(page.getByRole("button", { name: "Confirm cancellation" })).toBeDisabled();
  expect(cancellationOtpRequests).toBe(1);

  await page.getByLabel("Cancellation code").fill("483921");
  await page.getByRole("button", { name: "Confirm cancellation" }).click();
  await expect(page.getByText("Cancelled", { exact: true })).toBeVisible();
  expect(submittedOtp).toBe("483921");
});
