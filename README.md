# खानेपानी ERP — Admin-only Firebase + GitHub Pages

यो build मा पुरानो ERP को HTML, सबै मोड्युल र एउटै पुरानो Firestore master document path कायम राखिएको छ। Email/Password Admin login, UID profile जाँच, revision-checked transaction save र PWA shell समावेश छन्।

## अहिलेको Firebase अवस्थासँग मिलाउने क्रम

1. Firestore को हालको `users/{UID}` document मा `role` को type **string** र value `admin`, `active` को type **boolean** र value `true` हुनुपर्छ। तपाईंले बनाइसकेको root collection को नाम `users` (plural) हो।
2. Authentication → Sign-in method मा Email/Password enabled हुनुपर्छ। Admin user Authentication → Users मा देखिनुपर्छ।
3. पहिले पुरानो ERP बाट उपलब्ध भएमा Full Backup JSON डाउनलोड गरेर सुरक्षित राख्नुहोस्। पुरानो Firestore document `artifacts/khanepani-erp-hamro-sanstha/public/data/erp_state/master_v1` delete नगर्नुहोस्।
4. Firestore → Rules मा यस प्याकेजको `firestore.rules` राखेर Publish गर्नुहोस्। यो नियमले authenticated active admin लाई मात्र उक्त ERP master document read/write दिन्छ। Users profile client बाट edit गर्न दिँदैन।
5. GitHub repository root मा `index.html`, `manifest.webmanifest`, `sw.js`, `firestore.rules`, `icons/` राख्नुहोस्। GitHub Pages ले Firestore rules deploy गर्दैन; rules Firebase Console वा Firebase CLI बाट छुट्टै publish हुन्छन्.
6. GitHub → Settings → Pages → Deploy from branch → `main` → `/ (root)` → Save.
7. HTTPS Pages URL खोल्नुहोस् र Firebase Authentication मा बनाएको Email/Password बाट login गर्नुहोस्।
8. Login पछि cloud master document भएमा पुरानो data load हुनेछ। Cloud document नभएमा पुरानो local backup लाई Support & Backup बाट जाँचेर restore गर्नुहोस्; खाली database मा स्वतः overwrite हुँदैन।

## Multi-device र सीमा

प्रत्येक Admin व्यक्तिको अलग Firebase Authentication account र त्यसको UID का लागि root `users/{uid}` profile चाहिन्छ। सबैलाई Admin role दिइएको हुनाले सबैले सबै ERP data हेर्न/सम्पादन गर्न सक्छन्। पहुँच हटाउन Firebase Auth user disable गर्नुहोस् र profile को `active` false गर्नुहोस्।

यो संस्करण एउटै master document मा revision check र transaction प्रयोग गर्छ। एकै समयमा दुई device बाट save भए conflict हुँदा save रोक्छ; यो per-record database architecture होइन र स्वतः field-level merge गर्दैन। त्यसैले यसलाई high-concurrency production ERP भन्न मिल्दैन। धेरै concurrent operators का लागि पछि प्रत्येक module लाई छुट्टाछुट्टै Firestore records मा migrate गर्नुपर्छ।

PWA cache मा app shell मात्र cache हुन्छ; Firebase requests cache हुँदैनन्। Offline data-entry queue यस build मा छैन; तपाईंले online sync रोज्नुभएकोले online login आवश्यक छ।
