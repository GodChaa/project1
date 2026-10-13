# SE233 Project I — Rubric checklist

อ้างอิงเอกสาร `2026_233_project_I.pdf` หัวข้อ Functional requirements และ Scoring rubrics

| รายการ | สิ่งที่ทำในโปรเจกต์ | จุดตรวจหลัก |
|---|---|---|
| Vectorization Format | JPG/JPEG และ PNG → SVG ที่มี vector paths โดยใช้ Potrace | `ImageVectorizerTest`, `BatchConverterTest` |
| UI Navigation & View | Original/Vector ข้างกัน, Zoom In/Out/Fit, Back/Next | `EditorViewTest`, `EditorVisualTest`, `EditorControllerTest` |
| Configuration: Details & BG | Low/Medium/High เปลี่ยนการ trace, ลบพื้นหลังและ preview | `ImageVectorizerTest`, `PotraceConverterTest` |
| Configuration: Colors | Unlimited เก็บสีต้นฉบับ; Custom ตรวจสี จำกัด 5 ค่าเริ่มต้น 2; preview เมื่อเปลี่ยนค่า | `ImageVectorizerTest`, `EditorControllerTest` |
| Zip Handling | รับ ZIP หนึ่งไฟล์ แยกรูปหลายโฟลเดอร์ ข้าม metadata ของ macOS และล้างไฟล์ชั่วคราว | `InputImporterTest` |
| Preprocessing | ต่อภาพ: detect → configure Medium/2 → preview; หลายภาพทำขนานพร้อม progress | `BatchConverterTest`, `EditorControllerTest` |
| Export | เลือกโฟลเดอร์ด้วย OS chooser, SVG ทุกภาพ, progress และ parallel ตามเงื่อนไข | `BatchConverterTest`, `EditorControllerTest` |
| Additional feature | Export ZIP + CSV report รวมผลงานและค่าการแปลงแต่ละภาพ | `BatchConverterTest` |
| UX and UI | ปุ่มวาดด้วย JavaFX Pane/Shapes, preview ขนาดสัมพันธ์กัน, แจ้งข้อผิดพลาด และไม่ trace บน FX thread | `EditorViewTest`, `EditorVisualTest`, `EditorControllerTest` |

เงื่อนไข parallel ใช้เหมือนกันทั้ง preprocessing และ export: ต้องมีมากกว่า 1 ภาพ + Medium + Custom เท่านั้น ส่วนค่าอื่นทำงานทีละภาพบน background thread

## ลำดับสาธิต

1. เปิดแอปด้วย `./mvnw javafx:run` และลาก PNG/JPG หลายภาพเข้า drop zone ให้มีอย่างน้อยภาพสีเดียว ภาพ 2 สี และภาพมากกว่า 5 สี
2. ดู progress ที่ระบุ “in parallel” และ preview เริ่มต้น Medium/Custom 2; ภาพสีเดียวแสดง 1 โดยไม่ลดค่าของภาพอื่น
3. กด Next/Back ตรวจชื่อไฟล์และเลขลำดับ ลอง Zoom In/Out/Fit และยืนยันว่าภาพซ้ายขวา fit เท่ากัน
4. เปลี่ยน Low/Medium/High, Unlimited/Custom และจำนวนสี แล้วดู preview; ทดลอง Remove Background กับภาพที่มีพื้นหลังสีเรียบต่อเนื่องถึงขอบ
5. Export All ไปยังโฟลเดอร์ว่าง เปิด SVG จากโฟลเดอร์ที่ได้ แล้ว export ซ้ำเพื่อดูชื่อใหม่ที่ไม่ทับไฟล์เดิม
6. ลอง export หลายภาพด้วย Medium/Custom แล้วเทียบสถานะกับ High หรือ Unlimited ซึ่งต้องไม่แสดง “in parallel”
7. กลับ drop zone แล้วลาก ZIP ที่มีทั้ง JPG/PNG เพื่อทำขั้นตอนเดิม
8. ใช้ Export ZIP + report เปิด ZIP เพื่อตรวจ SVG และ `manifest.csv`
9. ทดลองรูปเสียหรือ ZIP ที่ไม่มีรูป ตรวจข้อความแจ้งข้อผิดพลาดและกลับไปนำเข้าชุดใหม่

## ขอบเขตการประเมิน

คะแนน Additional feature และ UX/UI ขึ้นอยู่กับผู้สอนตาม PDF ส่วนการลบพื้นหลังใช้สีบริเวณขอบภาพ ไม่ใช่ AI แยกวัตถุ จึงควรสาธิตกับภาพพื้นหลังสีเรียบและตรวจผลก่อนส่งงานจริง
