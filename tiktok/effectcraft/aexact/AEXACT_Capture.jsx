/*
  AEXACT Capture — снимает ЦК с настоящего After Effects для системы aexact.

  Что делает: для каждого пресета .ffx из папки presets/ применяет его к калибровочным картинкам из charts/
  и сохраняет кадры: весь стек и каждый эффект отдельно. Плюс manifest.json со списком эффектов.
  Результат — папка capture/<имя пресета>/. Её целиком нужно передать в aexact (compile).

  Как запустить: After Effects ▸ File ▸ Scripts ▸ Run Script File… ▸ этот файл.
  Папка набора: AEXACT_Capture.jsx, charts/ (hald.png, hald_shuffled.png, fit.png, test.png), presets/*.ffx
  Нужны установленные плагины, которые использует пресет (иначе эффект «Missing» и съёмка бессмысленна).
  Настройки проекта: 8 бит на канал, управление цветом выключено — скрипт выставляет сам.
  Preferences ▸ Scripting & Expressions ▸ Allow Scripts to Write Files — включить.
*/
(function () {
    var CHARTS = ["hald", "hald_shuffled", "fit", "test"];

    function q(s) { return '"' + String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\n/g, "\\n").replace(/\r/g, "") + '"'; }
    function pad2(n) { return (n < 10 ? "0" : "") + n; }
    function writeText(file, text) { file.encoding = "UTF-8"; file.open("w"); file.write(text); file.close(); }
    function shot(comp, file) {
        if (file.exists) { file.remove(); }
        comp.saveFrameToPng(0, file);
        var t = 0;
        while (!file.exists && t < 600) { $.sleep(100); t++; }   // кадр пишется не мгновенно
        $.sleep(200);
    }

    var kit = new File($.fileName).parent;
    var presets = new Folder(kit.fsName + "/presets").getFiles("*.ffx");
    if (!presets || presets.length === 0) { alert("AEXACT: положите пресеты .ffx в папку presets рядом со скриптом"); return; }

    app.beginUndoGroup("AEXACT Capture");
    try { app.project.bitsPerChannel = 8; } catch (e1) {}
    try { app.project.workingSpace = ""; } catch (e2) {}
    try { app.project.linearBlending = false; } catch (e3) {}

    var done = [];
    for (var p = 0; p < presets.length; p++) {
        var look = decodeURI(presets[p].name).replace(/\.ffx$/i, "");
        var out = new Folder(kit.fsName + "/capture/" + look);
        out.create();
        var effects = [], missing = [];
        for (var c = 0; c < CHARTS.length; c++) {
            var src = new File(kit.fsName + "/charts/" + CHARTS[c] + ".png");
            if (!src.exists) { alert("AEXACT: нет " + src.fsName); return; }
            var item = app.project.importFile(new ImportOptions(src));
            var comp = app.project.items.addComp("AEXACT " + look + " " + CHARTS[c], item.width, item.height, 1, 1, 30);
            var layer = comp.layers.add(item);
            layer.selected = true;
            comp.openInViewer();
            layer.applyPreset(presets[p]);
            var parade = layer.property("ADBE Effect Parade");
            var n = parade.numProperties, i, j;
            if (c === 0) {
                for (i = 1; i <= n; i++) {
                    var fx = parade.property(i);
                    effects.push('{"index": ' + i + ', "name": ' + q(fx.name) + ', "match": ' + q(fx.matchName) + ', "enabled": ' + (fx.enabled ? "true" : "false") + '}');
                    if (String(fx.name).indexOf("Missing") === 0) { missing.push(fx.matchName); }
                }
            }
            var was = [];
            for (i = 1; i <= n; i++) { was.push(parade.property(i).enabled); }
            shot(comp, new File(out.fsName + "/" + CHARTS[c] + "__full.png"));
            for (i = 1; i <= n; i++) {
                if (!was[i - 1]) { continue; }
                for (j = 1; j <= n; j++) { parade.property(j).enabled = (j === i); }
                shot(comp, new File(out.fsName + "/" + CHARTS[c] + "__fx" + pad2(i) + ".png"));
            }
            for (j = 1; j <= n; j++) { parade.property(j).enabled = was[j - 1]; }
        }
        var charts = new Folder(out.fsName + "/charts");
        charts.create();
        for (var k = 0; k < CHARTS.length; k++) {
            new File(kit.fsName + "/charts/" + CHARTS[k] + ".png").copy(charts.fsName + "/" + CHARTS[k] + ".png");
        }
        writeText(new File(out.fsName + "/manifest.json"),
            '{"look": ' + q(look) + ', "host": ' + q("After Effects " + app.version) + ', "charts": ["hald", "hald_shuffled", "fit", "test"],\n "missing": [' +
            (function () { var m = []; for (var x = 0; x < missing.length; x++) { m.push(q(missing[x])); } return m.join(", "); })() +
            '],\n "effects": [' + effects.join(",\n  ") + ']}');
        done.push(look + (missing.length ? "  (НЕТ ПЛАГИНОВ: " + missing.join(", ") + ")" : ""));
    }
    app.endUndoGroup();
    alert("AEXACT: снято пресетов — " + done.length + "\n" + done.join("\n") + "\n\nПапка: " + kit.fsName + "/capture");
})();
