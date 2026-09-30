import { expect, test } from "@playwright/test";

test("reconnection artwork animates, pauses, resumes and respects reduced motion", async ({ page }) => {
    const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Reconnecting to your workspace" })).toBeVisible();
    await expect.poll(() => page.locator("img").evaluateAll(images => images.every(image => image instanceof HTMLImageElement && image.complete && image.naturalWidth > 0))).toBe(true);
    const bridge = page.locator('[class*="bridgeModule"]');
    const before = await bridge.evaluate(element => getComputedStyle(element).transform);
    await expect.poll(() => bridge.evaluate(element => getComputedStyle(element).transform)).not.toBe(before);
    await page.getByRole("button", { name: "Pause animation" }).click();
    await expect(page.locator("main")).toHaveAttribute("data-motion", "paused");
    expect(await bridge.evaluate(element => getComputedStyle(element).animationPlayState)).toBe("paused");
    await page.getByRole("button", { name: "Resume animation" }).click();
    expect(await bridge.evaluate(element => getComputedStyle(element).animationPlayState)).toBe("running");
    await page.getByText("Connection help", { exact: true }).click();
    await expect(page.getByText("Check your internet connection, then try again.")).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.emulateMedia({ reducedMotion: "reduce" });
    expect(await bridge.evaluate(element => getComputedStyle(element).animationName)).toBe("none");
    await Promise.all([page.waitForEvent("load"), page.getByRole("button", { name: "Retry connection" }).click()]);
    await expect(page.getByRole("heading", { name: "Reconnecting to your workspace" })).toBeVisible();
    expect(errors).toEqual([]);
});
