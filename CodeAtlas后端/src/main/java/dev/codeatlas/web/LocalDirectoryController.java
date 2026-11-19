package dev.codeatlas.web;

import org.springframework.web.bind.annotation.*;
import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Desktop-only convenience: authorization and snapshot capture remain separate. */
@RestController
@RequestMapping("/api/local-directories")
public class LocalDirectoryController {
    private final AtomicBoolean selecting=new AtomicBoolean();
    private final java.util.function.BooleanSupplier headless;
    public LocalDirectoryController(){this(GraphicsEnvironment::isHeadless);}
    LocalDirectoryController(java.util.function.BooleanSupplier headless){this.headless=headless;}
    @PostMapping("/select") public Object select() {
        if(headless.getAsBoolean()) return Map.of("status","unavailable","reason","当前没有交互式桌面，请手工输入目录");
        if(!selecting.compareAndSet(false,true)) throw new ApiException(409,"DIRECTORY_SELECT_IN_PROGRESS","已有目录选择窗口，请先关闭");
        var cancelled=new AtomicBoolean(); var dialog=new AtomicReference<JDialog>();
        var answer=new CompletableFuture<Map<String,String>>();
        Runnable close=()->{cancelled.set(true);SwingUtilities.invokeLater(()->{var d=dialog.get();if(d!=null)d.dispose();});};
        try {
            SwingUtilities.invokeLater(()->{
                try {
                    if(cancelled.get()) {answer.complete(Map.of("status","cancelled"));return;}
                    var chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);chooser.setAcceptAllFileFilterUsed(false);
                    var window=new JDialog((Frame)null,"选择项目目录",true);dialog.set(window);window.setAlwaysOnTop(true);window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    chooser.addActionListener(event->{
                        if(JFileChooser.APPROVE_SELECTION.equals(event.getActionCommand()) && chooser.getSelectedFile()!=null && !cancelled.get())
                            answer.complete(Map.of("status","selected","path",chooser.getSelectedFile().getAbsolutePath()));
                        window.dispose();
                    });
                    window.add(chooser);window.pack();window.setLocationRelativeTo(null);
                    if(!cancelled.get())window.setVisible(true);
                    answer.complete(Map.of("status","cancelled"));
                } catch(Exception|AWTError error) {answer.complete(Map.of("status","unavailable","reason","目录窗口不可用，请手工输入目录"));}
                finally {var d=dialog.get();if(d!=null)d.dispose();selecting.set(false);}
            });
        } catch(RuntimeException|AWTError error) {selecting.set(false);return Map.of("status","unavailable","reason","目录窗口不可用，请手工输入目录");}
        try {return answer.get(120,TimeUnit.SECONDS);}
        catch(TimeoutException error) {close.run();throw new ApiException(408,"DIRECTORY_SELECT_TIMEOUT","目录选择超时，请关闭窗口后重试");}
        catch(InterruptedException error) {close.run();Thread.currentThread().interrupt();throw new ApiException(503,"DIRECTORY_SELECT_INTERRUPTED","目录选择已中断");}
        catch(ExecutionException error) {close.run();throw new ApiException(503,"DIRECTORY_SELECT_UNAVAILABLE","目录选择不可用");}
    }
}
