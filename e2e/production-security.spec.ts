import { expect, test } from "@playwright/test";

test("explicit lock mode fails closed when the backend URL is absent", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Reconnecting to your workspace" })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "Connection unavailable" })).toBeVisible();
  await expect(page.getByRole("button", { name: /sign in/i })).toHaveCount(0);
  await expect(page.getByText(/Preview OTP/i)).toHaveCount(0);
});

test("browser storage cannot restore a Preview administrator session", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem(
        "brainserve.demo.workspace.session.v1",
        JSON.stringify({ role: "System Admin", email: "jetychodipilli@gmail.com" }),
    );
  });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Reconnecting to your workspace" })).toBeVisible();
  await expect(page.getByText(/System Admin workspace/i)).toHaveCount(0);
});
