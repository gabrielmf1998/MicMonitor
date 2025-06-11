import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
// MicMonitor, criado por Gabriel Marques Ferrarezi

public class MicrophoneVolumeVisualizer {

    private static final String GITHUB_URL = "https://github.com/gabrielmf1998";
    private static final String LINKEDIN_URL = "https://www.linkedin.com/in/gabriel-marques-ferrarezi-8a0913190/";

    private static final int ICON_WIDTH = 32;
    private static final int ICON_HEIGHT = 32;
    private static final int BAR_COUNT = 7;

    private volatile boolean running = true;
    private TrayIcon trayIcon;
    private TargetDataLine microphoneLine;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new MicrophoneVolumeVisualizer().start();
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Ocorreu um erro ao iniciar: " + e.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public void start() throws Exception {
        if (!SystemTray.isSupported()) {
            System.err.println("A bandeja do sistema (System Tray) não é suportada.");
            return;
        }

        Mixer.Info selectedMixerInfo = selectMicrophone();
        if (selectedMixerInfo == null) {
            System.out.println("Nenhum microfone selecionado. Encerrando.");
            return;
        }

        setupTrayIcon();
        startMonitoring(selectedMixerInfo);
    }

    private Mixer.Info selectMicrophone() {
        List<Mixer.Info> microphoneMixers = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(new DataLine.Info(TargetDataLine.class, null))) {
                microphoneMixers.add(info);
            }
        }

        if (microphoneMixers.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Nenhum microfone encontrado.", "Erro", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        String[] mixerNames = microphoneMixers.stream().map(Mixer.Info::getName).toArray(String[]::new);
        String selectedName = (String) JOptionPane.showInputDialog(
                null, "Escolha o microfone para monitorar:", "Seleção de Microfone",
                JOptionPane.QUESTION_MESSAGE, null, mixerNames, mixerNames[0]);

        if (selectedName == null) return null;

        return microphoneMixers.stream()
                .filter(info -> info.getName().equals(selectedName))
                .findFirst().orElse(null);
    }

    private void setupTrayIcon() throws AWTException {
        SystemTray tray = SystemTray.getSystemTray();
        PopupMenu popup = new PopupMenu();

        // Item do GitHub
        MenuItem githubItem = new MenuItem("GitHub");
        githubItem.addActionListener(e -> openWebpage(GITHUB_URL));
        popup.add(githubItem);

        // Item do LinkedIn
        MenuItem linkedinItem = new MenuItem("LinkedIn");
        linkedinItem.addActionListener(e -> openWebpage(LINKEDIN_URL));
        popup.add(linkedinItem);
        
        // Separador para organização
        popup.addSeparator();

        // Item de Saída
        MenuItem exitItem = new MenuItem("Sair");
        exitItem.addActionListener(e -> {
            running = false;
            tray.remove(trayIcon);
            System.exit(0);
        });
        popup.add(exitItem);

        Image initialImage = createVolumeIcon(0);
        trayIcon = new TrayIcon(initialImage, "Monitor de Volume do Microfone", popup);
        trayIcon.setImageAutoSize(true);
        tray.add(trayIcon);
    }

    private void openWebpage(String urlString) {
        if (urlString == null || urlString.startsWith("URL_AQUI")) {
            JOptionPane.showMessageDialog(null, "A URL não foi configurada no código-fonte.", "URL não definida", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(urlString));
            } else {
                 JOptionPane.showMessageDialog(null, "Ação não suportada neste sistema.", "Erro", JOptionPane.ERROR_MESSAGE);
            }
        } catch (IOException | URISyntaxException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(null, "Não foi possível abrir o link.", "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void startMonitoring(Mixer.Info mixerInfo) throws LineUnavailableException {
        AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
        DataLine.Info dataLineInfo = new DataLine.Info(TargetDataLine.class, format);
        microphoneLine = (TargetDataLine) AudioSystem.getLine(dataLineInfo);
        microphoneLine.open(format);
        microphoneLine.start();

        Thread monitoringThread = new Thread(() -> {
            byte[] buffer = new byte[microphoneLine.getBufferSize() / 5];
            while (running) {
                int bytesRead = microphoneLine.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    double volume = calculateRMSVolume(buffer, bytesRead);
                    updateTrayIcon(volume);
                }
            }
            microphoneLine.stop();
            microphoneLine.close();
        });
        monitoringThread.setDaemon(true);
        monitoringThread.start();
    }

    private double calculateRMSVolume(byte[] audioData, int bytesRead) {
        long sumOfSquares = 0;
        for (int i = 0; i < bytesRead; i += 2) {
            short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            sumOfSquares += (long) sample * sample;
        }
        double meanSquare = (double) sumOfSquares / (bytesRead / 2.0);
        double rms = Math.sqrt(meanSquare);
        double normalizedVolume = (rms / 32767.0) * 200;
        return Math.min(normalizedVolume, 100.0);
    }

    private void updateTrayIcon(double volume) {
        Image newImage = createVolumeIcon((int) volume);
        SwingUtilities.invokeLater(() -> trayIcon.setImage(newImage));
    }

    private Image createVolumeIcon(int volume) {
        BufferedImage image = new BufferedImage(ICON_WIDTH, ICON_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int barsToLight = (int) Math.ceil((volume / 100.0) * BAR_COUNT);
        int barWidth = (ICON_WIDTH - (BAR_COUNT + 1)) / BAR_COUNT;
        int spacing = 1;

        for (int i = 0; i < BAR_COUNT; i++) {
            if (i < barsToLight) {
                if (i < BAR_COUNT * 0.5) g2d.setColor(Color.GREEN);
                else if (i < BAR_COUNT * 0.8) g2d.setColor(Color.YELLOW);
                else g2d.setColor(Color.RED);
            } else {
                g2d.setColor(Color.DARK_GRAY.brighter());
            }
            int x = spacing + i * (barWidth + spacing);
            int barHeight = 4 + (int) ((ICON_HEIGHT - 8) * ((i + 1.0) / BAR_COUNT));
            int y = ICON_HEIGHT - barHeight;
            g2d.fillRect(x, y, barWidth, barHeight);
        }
        g2d.dispose();
        return image;
    }
}