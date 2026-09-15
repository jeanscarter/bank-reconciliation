using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

namespace BankReconciliationLauncher
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            try
            {
                string appDir = AppDomain.CurrentDomain.BaseDirectory;
                
                // 1. Locate Jar
                string jarPath = null;
                string workingDir = null;

                string[] candidateJars = new string[]
                {
                    Path.Combine(appDir, "bank-reconciliation-1.0-SNAPSHOT.jar"),
                    Path.Combine(appDir, "target", "bank-reconciliation-1.0-SNAPSHOT.jar"),
                    @"C:\Users\Jean\Desktop\bank-reconciliation\target\bank-reconciliation-1.0-SNAPSHOT.jar"
                };

                foreach (string candidate in candidateJars)
                {
                    if (File.Exists(candidate))
                    {
                        jarPath = candidate;
                        workingDir = Path.GetDirectoryName(Path.GetDirectoryName(candidate));
                        if (string.IsNullOrEmpty(workingDir) || !Directory.Exists(workingDir))
                        {
                            workingDir = Path.GetDirectoryName(candidate);
                        }
                        break;
                    }
                }

                if (jarPath == null)
                {
                    MessageBox.Show(
                        "No se encontró el archivo JAR de la aplicación.\nBuscado en: " + string.Join("\n", candidateJars),
                        "Conciliación Bancaria - Error",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error);
                    return;
                }

                // 2. Locate javaw.exe
                string javaPath = null;
                string[] candidateJavas = new string[]
                {
                    @"C:\Program Files\Java\jdk-26.0.2.1\bin\javaw.exe",
                    Path.Combine(Environment.GetEnvironmentVariable("JAVA_HOME") ?? "", "bin", "javaw.exe"),
                    "javaw.exe"
                };

                foreach (string candidate in candidateJavas)
                {
                    if (!string.IsNullOrEmpty(candidate) && (candidate == "javaw.exe" || File.Exists(candidate)))
                    {
                        javaPath = candidate;
                        break;
                    }
                }

                if (javaPath == null)
                {
                    javaPath = "javaw.exe";
                }

                // 3. Launch Process
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = javaPath;
                psi.Arguments = "-jar \"" + jarPath + "\"";
                psi.WorkingDirectory = @"C:\Users\Jean\Desktop\bank-reconciliation";
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;

                Process.Start(psi);
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    "Error al iniciar Conciliación Bancaria:\n" + ex.Message,
                    "Error de Ejecución",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
        }
    }
}
