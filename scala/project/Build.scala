import sbt._

object Build extends sbt.Build {
  lazy val root = Project(
		id = "asterisque",
		base = file(".")
	) aggregate(ri)

	lazy val ri = Project(
		id = "asterisque-ri",
		base = file("ri")
	)
}
