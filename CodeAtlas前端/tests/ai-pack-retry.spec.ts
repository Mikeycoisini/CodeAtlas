import { test, expect } from "@playwright/test";

test("fresh workspace selects a file and retries AI preparation without sending to a provider",async({page})=>{
 let preparations=0;let sourceCalls=0;let adoptions=0;let directWrites=0;
 const provider={provider:"fixture",model:"fixture",baseUrl:"https://provider.test",maxTokens:1000,configured:true,fingerprint:"x"};
 const source={id:"source1",sourceId:"source1",projectId:"p1",snapshotId:"s1",generationId:"g1",fileId:"f1",filePath:"Demo.java",sha256:"a".repeat(64),startLine:1,endLine:1,content:"class Demo {}"};
 await page.route("**/api/**",async route=>{
  const path=new URL(route.request().url()).pathname;
  let body:unknown={items:[],total:0};
  if(path==="/api/session")body={token:"test"};
  else if(path==="/api/projects")body=[{id:"p1",name:"Demo",sourcePath:"C:/demo"}];
  else if(path==="/api/projects/p1/snapshots")body=[{id:"s1",projectId:"p1",manifestHash:"abc",sourceKind:"LOCAL",createdAt:"2026-11-19T00:00:00Z",publishedGeneration:"g1",latestTask:null}];
  else if(path==="/api/snapshots/s1/files")body={items:[{id:"f1",path:"Demo.java",byteSize:13}],total:1,generationId:"g1"};
  else if(path.includes("/content"))body={fileId:"f1",snapshotId:"s1",path:"Demo.java",content:"class Demo {}",sha256:"a".repeat(64),lineCount:1};
  else if(path==="/api/ai/status")body={...provider,reachable:true,capabilities:[]};
  else if(path==="/api/ai/provider")body=provider;
  else if(path==="/api/sources"){
   sourceCalls++;if(sourceCalls===1){await route.fulfill({status:503,json:{code:"TEMPORARY",message:"preparation failed"}});return;}body=source;
  }
  else if(path==="/api/training/ai/contexts"){
   preparations++;body={id:"c1",sources:[source],provider,expiresAt:"2030-01-01T00:00:00Z",characterCount:13,byteCount:13,contextJson:"{}"};
  }
  if(path==="/api/ai/consents")body={id:"consent1"};
  if(path==="/api/ai/generations")body={id:"generation1",status:"SUCCEEDED",provider,result:{title:"Read Demo",goal:"Read code",constraints:"",acceptanceCriteria:"Explain",knowledge:[],questions:[{prompt:"Read?"}]}};
  if(path==="/api/training/ai/generations/generation1/learning-pack"){adoptions++;body={trainingId:"t1",knowledge:1,cards:1};}
  if(["/api/knowledge","/api/cards"].includes(path)&&route.request().method()==="POST")directWrites++;
  await route.fulfill({json:body});
 });
 await page.goto("/?view=ai&projectId=p1&snapshotId=s1&generationId=g1");
 await page.getByLabel("源码文件",{exact:true}).selectOption("f1");
 await page.getByRole("button",{name:"使用整个文件",exact:true}).click();
 await page.getByRole("button",{name:"生成训练草稿",exact:true}).click();
 await expect(page.getByRole("alert")).toContainText("preparation failed");
 await page.getByRole("button",{name:"重试准备"}).click();
 await expect(page.getByRole("heading",{name:"确认外发范围"})).toBeVisible();
 expect(sourceCalls).toBe(2);expect(preparations).toBe(1);
 await expect(page.getByRole("button",{name:"授权并生成"})).toBeDisabled();
 await page.getByRole("checkbox").check();
 await page.getByRole("button",{name:"授权并生成"}).click();
 await page.getByRole("button",{name:"保存知识、卡片与训练草稿"}).click();
 await expect(page.getByRole("status")).toContainText("已写入 1 条知识");
 expect(adoptions).toBe(1);expect(directWrites).toBe(0);
});

test("clicking local import opens picker and clears authorization",async({page})=>{
 let selected=0;
 await page.route("**/api/**",async route=>{
  const path=new URL(route.request().url()).pathname;
  if(path==="/api/local-directories/select"){selected++;await route.fulfill({json:{status:"selected",path:"C:/demo project"}});return;}
  await route.fulfill({json:path==="/api/session"?{token:"test"}:path==="/api/projects"?[]:{items:[],total:0}});
 });
 await page.goto("/");
 await page.getByRole("button",{name:"导入项目",exact:true}).click();
 await page.getByRole("tab",{name:"本地目录"}).click();
 await expect(page.getByPlaceholder("D:\\Projects\\my-spring-project")).toHaveValue("C:/demo project");
 expect(selected).toBe(1);
 await expect(page.getByRole("checkbox")).not.toBeChecked();
});
