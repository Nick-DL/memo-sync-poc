import urllib.request
from lxml import html
u='https://developer.android.google.cn/build/migrate-to-built-in-kotlin?hl=en'
req=urllib.request.Request(u,headers={'User-Agent':'Mozilla/5.0'})
r=urllib.request.urlopen(req,timeout=60)
data=r.read()
print(r.status,len(data))
doc=html.fromstring(data)
node=doc.xpath('//article') or doc.xpath('//main')
text=node[0].text_content() if node else doc.text_content()
text='\n'.join(line.strip() for line in text.splitlines() if line.strip())
open(r'memo-sync-poc\.toolchain\builtin-kotlin.txt','w',encoding='utf-8').write(text)
for i,line in enumerate(text.splitlines()):
    if any(k in line.lower() for k in ['org.jetbrains.kotlin.android','built-in kotlin','compose compiler','kotlinoptions','compileroptions','plugins {','kotlin {','android {']):
        print(f'{i}: {line}')
