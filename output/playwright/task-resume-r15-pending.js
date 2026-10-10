async (page) => {
  const steps = [];
  const box = page.getByRole("textbox", { name: "例如：查询销售报表 / 查询失败的派单记录 / 总结A公司工单（Enter 发送，Shift+Enter 换行）", exact: true });
  async function capture(id, message) {
    const screenshot = "output/playwright/r15-" + id + ".png";
    await page.screenshot({ path: screenshot, fullPage: true, scale: "css" });
    steps.push({ id, message, screenshot, text: await page.locator(".chat-messages").innerText(), pending: await page.locator(".plan-card.pending").count(), cancelled: await page.locator(".plan-card.cancelled").count(), previews: await page.locator(".agent-card").filter({hasText:"可派单记录预览"}).count() });
  }
  async function ask(message) {
    const before = await page.locator(".chat-messages .msg").count();
    await box.fill(message);
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction((count) => {
      const input = document.querySelector(".chat-input textarea");
      return input && !input.disabled && document.querySelectorAll(".chat-messages .msg").length > count && !document.querySelector(".chat-messages .typing");
    }, before, { timeout: 120000 });
  }
  async function assertOnePending() {
    const pending = page.locator(".plan-card.pending");
    if (await pending.count() !== 1) throw new Error("没有唯一待确认清单");
    const text = await pending.innerText();
    if (!text.includes("SO2026001") || /SO2026002|SO2026007|SO2026008/.test(text) || !/1\s*条/.test(text)) throw new Error("待确认清单不是精确的一条服务器订单");
  }
  try {
    await ask("把这条数据生成待确认派单清单，先不要执行");
    await assertOnePending();
    await capture("03-first-pending", "把这条数据生成待确认派单清单，先不要执行");
    await page.locator(".plan-card.pending").getByRole("button", { name: "取消", exact: true }).click();
    await page.locator(".plan-card.cancelled").first().waitFor({ state: "visible", timeout: 20000 });
    await capture("04-cancelled", "点击清单取消按钮");
    const previews = await page.locator(".agent-card").filter({hasText:"可派单记录预览"}).count();
    await ask("查看刚才已取消的清单");
    if (await page.locator(".agent-card").filter({hasText:"可派单记录预览"}).count() !== previews || await page.locator(".plan-card.pending").count()) throw new Error("查看已取消清单产生新候选或清单");
    await capture("05-show-cancelled", "查看刚才已取消的清单");
    await ask("重新核对刚才那条服务器订单的可派单候选");
    await capture("06-rechecked-preview", "重新核对刚才那条服务器订单的可派单候选");
    await ask("按刚核对的这一条重新生成待确认清单");
    await assertOnePending();
    await capture("07-second-pending", "按刚核对的这一条重新生成待确认清单");
    return { version: "r15", phase: "prepare-cancel-reprepare", passed: true, steps };
  } catch (error) {
    await capture("pending-failure", String(error.message));
    return { version: "r15", phase: "prepare-cancel-reprepare", passed: false, error: String(error.message), steps };
  }
}
