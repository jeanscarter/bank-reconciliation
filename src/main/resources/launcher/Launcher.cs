using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

namespace BankReconciliationLauncher
{
    static class Program
    {
        private const string APP_NAME = "Conciliación Bancaria";
        private const string JAR_FILENAME = "bank-reconciliation-1.0-SNAPSHOT.jar";
        private const string EXE_FILENAME = "ConciliacionBancaria.exe";
        private const string ICON_FILENAME = "app-icon.ico";

        [STAThread]
        static void Main()
        {
            try
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                // 1. Directorio de destino local: Documentos del usuario que ejecuta el programa
                string myDocs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
                string localAppDir = Path.Combine(myDocs, "ConciliacionBancaria");
                if (!Directory.Exists(localAppDir))
                {
                    Directory.CreateDirectory(localAppDir);
                }

                // 2. Identificar directorio del servidor / repositorio de origen
                string appDir = AppDomain.CurrentDomain.BaseDirectory;
                string serverSourceDir = FindServerSource(appDir, localAppDir);

                string localJar = Path.Combine(localAppDir, JAR_FILENAME);
                string localExe = Path.Combine(localAppDir, EXE_FILENAME);
                string localIcon = Path.Combine(localAppDir, ICON_FILENAME);

                bool updateNeeded = false;
                string serverJar = null;

                if (!string.IsNullOrEmpty(serverSourceDir) && Directory.Exists(serverSourceDir))
                {
                    serverJar = FindServerJar(serverSourceDir);
                    if (!string.IsNullOrEmpty(serverJar) && File.Exists(serverJar))
                    {
                        if (!File.Exists(localJar))
                        {
                            updateNeeded = true;
                        }
                        else
                        {
                            FileInfo sInfo = new FileInfo(serverJar);
                            FileInfo lInfo = new FileInfo(localJar);
                            // Si el archivo del servidor tiene fecha de modificación más reciente o tamaño distinto
                            if (sInfo.LastWriteTimeUtc > lInfo.LastWriteTimeUtc.AddSeconds(2) || sInfo.Length != lInfo.Length)
                            {
                                updateNeeded = true;
                            }
                        }
                    }
                }

                // 3. Si se requiere actualizar, mostrar ventana discreta con progreso
                if (updateNeeded)
                {
                    ShowUpdateAndSync(serverSourceDir, serverJar, localAppDir, localJar, localExe, localIcon);
                }
                else if (!string.IsNullOrEmpty(serverSourceDir))
                {
                    // Asegurar archivos auxiliares (icono, exe local) si faltan
                    EnsureAuxiliaryFiles(serverSourceDir, localAppDir, localExe, localIcon);
                }

                // 4. Crear acceso directo en el Escritorio si aún no existe
                CreateDesktopShortcut(localExe, localIcon, localAppDir);

                // 5. Verificar que el JAR local exista antes de ejecutar
                if (!File.Exists(localJar))
                {
                    if (!string.IsNullOrEmpty(serverJar) && File.Exists(serverJar))
                    {
                        File.Copy(serverJar, localJar, true);
                    }
                    else
                    {
                        MessageBox.Show(
                            "No se encontró el archivo de la aplicación en Documentos ni se pudo conectar con el servidor.\n\n" +
                            "Ruta esperada:\n" + localJar,
                            APP_NAME + " - Error",
                            MessageBoxButtons.OK,
                            MessageBoxIcon.Error);
                        return;
                    }
                }

                // 6. Localizar Java (javaw.exe)
                string javaPath = FindJavaExecutable();
                if (string.IsNullOrEmpty(javaPath))
                {
                    MessageBox.Show(
                        "No se encontró una instalación de Java (JDK/JRE) en este equipo.\n\n" +
                        "Por favor instale Java (versión 17 o superior) para poder ejecutar " + APP_NAME + ".",
                        APP_NAME + " - Java no detectado",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Warning);
                    return;
                }

                // 7. Ejecutar aplicación localmente desde Documentos
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = javaPath;
                psi.Arguments = "-jar \"" + localJar + "\"";
                psi.WorkingDirectory = localAppDir;
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;

                Process.Start(psi);
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    "Error al iniciar " + APP_NAME + ":\n\n" + ex.Message,
                    "Error de Ejecución",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
        }

        private static string FindServerSource(string currentAppDir, string localAppDir)
        {
            // Si el ejecutable no se abrió desde Documentos y tiene el JAR/target, su ruta es el servidor
            if (!PathsEqual(currentAppDir, localAppDir))
            {
                if (File.Exists(Path.Combine(currentAppDir, "target", JAR_FILENAME)) ||
                    File.Exists(Path.Combine(currentAppDir, JAR_FILENAME)) ||
                    File.Exists(Path.Combine(currentAppDir, "pom.xml")))
                {
                    return currentAppDir;
                }
            }

            // Rutas conocidas de red y compartidas en el servidor
            string[] knownServerPaths = new string[]
            {
                @"X:\bank-reconciliation",
                @"\\SRVFS0101\Sistemas\bank-reconciliation",
                @"//SRVFS0101/Sistemas/bank-reconciliation"
            };

            foreach (string p in knownServerPaths)
            {
                try
                {
                    if (Directory.Exists(p))
                    {
                        return p;
                    }
                }
                catch { }
            }

            return null;
        }

        private static string FindServerJar(string serverDir)
        {
            if (string.IsNullOrEmpty(serverDir)) return null;

            string targetJar = Path.Combine(serverDir, "target", JAR_FILENAME);
            if (File.Exists(targetJar)) return targetJar;

            string rootJar = Path.Combine(serverDir, JAR_FILENAME);
            if (File.Exists(rootJar)) return rootJar;

            return null;
        }

        private static void ShowUpdateAndSync(string serverDir, string serverJar, string localAppDir, string localJar, string localExe, string localIcon)
        {
            using (UpdateForm form = new UpdateForm())
            {
                form.StartSync(() =>
                {
                    try
                    {
                        File.Copy(serverJar, localJar, true);
                        EnsureAuxiliaryFiles(serverDir, localAppDir, localExe, localIcon);
                    }
                    catch { }
                });

                form.ShowDialog();
            }
        }

        private static void EnsureAuxiliaryFiles(string serverDir, string localAppDir, string localExe, string localIcon)
        {
            try
            {
                if (string.IsNullOrEmpty(serverDir) || !Directory.Exists(serverDir)) return;

                // Copiar icono
                string serverIcon = Path.Combine(serverDir, "src", "main", "resources", "icons", ICON_FILENAME);
                if (!File.Exists(serverIcon))
                {
                    serverIcon = Path.Combine(serverDir, ICON_FILENAME);
                }
                if (File.Exists(serverIcon))
                {
                    File.Copy(serverIcon, localIcon, true);
                }

                // Copiar ejecutable
                string serverExe = Path.Combine(serverDir, EXE_FILENAME);
                if (File.Exists(serverExe))
                {
                    // Solo copiar si no es el mismo archivo que se está ejecutando actualmente
                    string runningExe = Process.GetCurrentProcess().MainModule.FileName;
                    if (!PathsEqual(runningExe, localExe))
                    {
                        File.Copy(serverExe, localExe, true);
                    }
                }
            }
            catch { }
        }

        private static void CreateDesktopShortcut(string targetExe, string iconPath, string workingDir)
        {
            try
            {
                string desktop = Environment.GetFolderPath(Environment.SpecialFolder.Desktop);
                string shortcutPath = Path.Combine(desktop, APP_NAME + ".lnk");

                // Si ya existe y apunta al ejecutable local, no reescribir
                if (File.Exists(shortcutPath))
                {
                    return;
                }

                Type shellType = Type.GetTypeFromProgID("WScript.Shell");
                if (shellType != null)
                {
                    object shell = Activator.CreateInstance(shellType);
                    object shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { shortcutPath });
                    Type scType = shortcut.GetType();
                    scType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut, new object[] { targetExe });
                    scType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut, new object[] { workingDir });
                    scType.InvokeMember("Description", BindingFlags.SetProperty, null, shortcut, new object[] { APP_NAME + " - Sistema Integral" });
                    if (File.Exists(iconPath))
                    {
                        scType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut, new object[] { iconPath });
                    }
                    scType.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
                }
            }
            catch { }
        }

        private static string FindJavaExecutable()
        {
            // 1. JAVA_HOME
            string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrEmpty(javaHome))
            {
                string javaw = Path.Combine(javaHome, "bin", "javaw.exe");
                if (File.Exists(javaw)) return javaw;
                string java = Path.Combine(javaHome, "bin", "java.exe");
                if (File.Exists(java)) return java;
            }

            // 2. Escanear carpetas estándar de JDK / JRE ordenadas descendente por versión más reciente
            string[] baseDirs = new string[]
            {
                @"C:\Program Files\Java",
                @"C:\Program Files\Eclipse Adoptium",
                @"C:\Program Files\BellSoft",
                @"C:\Program Files\Amazon Corretto",
                @"C:\Program Files\Zulu",
                @"C:\Program Files (x86)\Java"
            };

            foreach (string baseDir in baseDirs)
            {
                if (Directory.Exists(baseDir))
                {
                    try
                    {
                        string[] subDirs = Directory.GetDirectories(baseDir);
                        Array.Sort(subDirs, StringComparer.OrdinalIgnoreCase);
                        Array.Reverse(subDirs); // Más reciente primero

                        foreach (string sub in subDirs)
                        {
                            string javaw = Path.Combine(sub, "bin", "javaw.exe");
                            if (File.Exists(javaw)) return javaw;
                            string java = Path.Combine(sub, "bin", "java.exe");
                            if (File.Exists(java)) return java;
                        }
                    }
                    catch { }
                }
            }

            // 3. Fallback: javaw en PATH
            return "javaw.exe";
        }

        private static bool PathsEqual(string path1, string path2)
        {
            if (string.IsNullOrEmpty(path1) || string.IsNullOrEmpty(path2)) return false;
            try
            {
                string full1 = Path.GetFullPath(path1).TrimEnd('\\', '/');
                string full2 = Path.GetFullPath(path2).TrimEnd('\\', '/');
                return string.Equals(full1, full2, StringComparison.OrdinalIgnoreCase);
            }
            catch
            {
                return false;
            }
        }
    }

    /// <summary>
    /// Ventana de notificación discreta con barra de progreso mientras se actualiza
    /// </summary>
    class UpdateForm : Form
    {
        private ProgressBar progressBar;
        private Label lblTitle;
        private Label lblStatus;
        private Action syncAction;
        private Exception syncException;

        public UpdateForm()
        {
            InitializeComponent();
        }

        private void InitializeComponent()
        {
            this.Text = "Conciliación Bancaria - Actualización";
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.StartPosition = FormStartPosition.CenterScreen;
            this.ClientSize = new Size(430, 140);
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.ShowIcon = true;
            this.BackColor = Color.FromArgb(248, 250, 252);

            lblTitle = new Label();
            lblTitle.Text = "Actualizando Conciliación Bancaria...";
            lblTitle.Font = new Font("Segoe UI", 11.5f, FontStyle.Bold);
            lblTitle.ForeColor = Color.FromArgb(15, 23, 42);
            lblTitle.Location = new Point(24, 20);
            lblTitle.Size = new Size(380, 26);

            lblStatus = new Label();
            lblStatus.Text = "Descargando la versión más reciente del servidor...";
            lblStatus.Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);
            lblStatus.ForeColor = Color.FromArgb(71, 85, 105);
            lblStatus.Location = new Point(25, 48);
            lblStatus.Size = new Size(380, 22);

            progressBar = new ProgressBar();
            progressBar.Style = ProgressBarStyle.Marquee;
            progressBar.MarqueeAnimationSpeed = 25;
            progressBar.Location = new Point(26, 78);
            progressBar.Size = new Size(378, 24);

            this.Controls.Add(lblTitle);
            this.Controls.Add(lblStatus);
            this.Controls.Add(progressBar);
        }

        public void StartSync(Action action)
        {
            this.syncAction = action;
            this.Shown += (s, e) =>
            {
                ThreadPool.QueueUserWorkItem(_ =>
                {
                    try
                    {
                        if (syncAction != null)
                        {
                            syncAction();
                        }
                    }
                    catch (Exception ex)
                    {
                        syncException = ex;
                    }
                    finally
                    {
                        try
                        {
                            this.BeginInvoke((MethodInvoker)delegate
                            {
                                this.Close();
                            });
                        }
                        catch { }
                    }
                });
            };
        }
    }
}
