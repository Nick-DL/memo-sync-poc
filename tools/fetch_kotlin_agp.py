import urllib.request
from lxml import html
u='https://developer.android.google.cn/build/kotlin-support?hl=en'
req=urllib.request.Request(u,headers={'User-Agent':'Mozilla/5.0'})
data=urllib.request.urlopen(req,timeout=60).read()
doc=html.fromstring(data)
for table in doc.xpath('//table'):
    text=table.text_content()
    if 'Kotlin' in text and 'AGP' in text:
        print(text[:5000])
