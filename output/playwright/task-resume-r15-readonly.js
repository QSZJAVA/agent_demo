async (page) => {
  const steps = [];
  const box = page.getByRole("textbox", { name: "例如：查询销售报表 / 查询失败的派单记录 / 总结A公司工单（Enter 发送，Shift+Enter 换行）", exact: true });
  async function ask(message, id) {
    const before = await page.locator(".chat-messages .msg").count();
    await box.fill(message);
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await page.waitForFunction((count) => {
      const input = document.querySelector(".chat-input textarea");
      return input && !input.disabled && document.querySelectorAll(".chat-messages .msg").length > count && !document.querySelector(".chat-messages .typing");
    }, before, { timeout: 120000 });
    const text = await page.locator(".chat-messages").innerText();
    const screenshot = "output/playwright/r15-" + id + ".png";
    await page.screenshot({ path: screenshot, fullPage: true, scale: "css" });
    steps.push({ id, message, text, screenshot, previewCards: await page.locator(".agent-card").filter({hasText: "可派单记录预览"}).count(), planCards: await page.locator(".plan-card").count() });
    return text;
  }
  try {
  const first = await ask("查一下销售报表服务器的数据", "01-original-query");
  if (!first.includes("SO2026001") || /SO2026002|SO2026007|SO2026008/.test(first)) throw new Error("原查询未精确定位服务器订单");
  if (steps[0].previewCards || steps[0].planCards) throw new Error("只读查询产生候选或清单");
  const eligibility = await ask("这条数据符合派单条件吗", "02-eligibility");
  if (!eligibility.includes("符合") || steps[1].previewCards || steps[1].planCards) throw new Error("资格核验结果或副作用边界不符");
  return { version: "r15", phase: "readonly", passed: true, steps };
  } catch (error) { return {version:"r15",phase:"readonly",passed:false,error:String(error),steps}; }
}
